package br.com.rdamasio.helpagent.extracao;

import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import br.com.rdamasio.helpagent.common.Documento;
import br.com.rdamasio.helpagent.config.HelpAgentProperties;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Uma chamada à API {@code generateContent} do Gemini. Não repete nada sozinho —
 * a política de tentativas e fallback fica no {@link ExtratorIa}.
 *
 * <p>A chave vai no cabeçalho {@code x-goog-api-key}, nunca na URL, e só existe no servidor.
 */
@Component
public class GeminiClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiClient.class);
    private static final List<Integer> STATUS_RETENTAVEL = List.of(408, 425, 429, 500, 502, 503, 504);
    private static final Pattern MODELO_SUGERIDO = Pattern.compile("models/(gemini[\\w.\\-]+)");

    /**
     * Formato fixo da resposta (structured output): a API garante um JSON com estes campos — acaba com a
     * nova tentativa por "resposta não é JSON válido". Espelha {@link DadosExtraidos}; mudou um, mude o outro.
     * Avaliado em 25/09/2026 contra orçamentos reais (tools/avaliar_extracao.py): mesma precisão e tempo do
     * formato livre, e a API aceitou nos dois modelos.
     */
    private static final Map<String, Object> ESQUEMA_RESPOSTA = esquema();

    private static Map<String, Object> esquema() {
        Map<String, Object> texto = Map.of("type", "string");
        Map<String, Object> item = Map.of("type", "object",
                "properties", Map.of("produto", texto, "descricao", texto, "qtd", texto, "valor_unit", texto,
                        "valor_total", texto, "fonte", texto),
                "required", List.of("produto", "descricao", "qtd", "valor_unit", "valor_total", "fonte"));
        Map<String, Object> propriedades = new LinkedHashMap<>();
        for (String campo : List.of("chamado_num", "loja_num", "loja_nome", "titulo")) propriedades.put(campo, texto);
        propriedades.put("itens", Map.of("type", "array", "items", item));
        for (String campo : List.of("total", "observacao", "validade_ate", "validade_dias")) propriedades.put(campo, texto);
        return Map.of("type", "object", "properties", propriedades,
                "required", List.of("titulo", "itens", "total", "observacao", "validade_ate", "validade_dias"));
    }

    private final HelpAgentProperties.Gemini cfg;
    private final RestClient http;
    private final JsonMapper json;

    public GeminiClient(HelpAgentProperties props, JsonMapper json) {
        this.cfg = props.gemini();
        this.json = json;
        SimpleClientHttpRequestFactory fabrica = new SimpleClientHttpRequestFactory();
        fabrica.setConnectTimeout(java.time.Duration.ofSeconds(15));
        fabrica.setReadTimeout(cfg.timeout());
        this.http = RestClient.builder().baseUrl(cfg.urlBase()).requestFactory(fabrica).build();
    }

    public boolean configurado() {
        return cfg.apiKey() != null && !cfg.apiKey().isBlank();
    }

    /**
     * @param configMinima sem {@code thinkingConfig}/{@code responseMimeType}/{@code responseJsonSchema}, para quando
     *                     a API recusa algum desses campos (o {@link ExtratorIa} repete assim uma vez)
     * @return o texto gerado (esperado: JSON)
     */
    public String gerar(String modelo, List<Documento> documentos, String prompt, boolean configMinima) {
        if (!configurado()) {
            throw new FalhaIa("A chave da API Gemini não está configurada no servidor (GEMINI_API_KEY).", 0, false);
        }

        List<Map<String, Object>> parts = new ArrayList<>();
        for (Documento d : documentos) {
            parts.add(Map.of("inline_data", Map.of(
                    "mime_type", d.mimeType(),
                    "data", Base64.getEncoder().encodeToString(d.conteudo()))));
        }
        parts.add(Map.of("text", prompt));

        Map<String, Object> generationConfig = new LinkedHashMap<>();
        generationConfig.put("maxOutputTokens", cfg.maxOutputTokens());
        if (!configMinima) {
            // Extrair campos de um print pede pouco raciocínio: menos latência e menos custo.
            generationConfig.put("thinkingConfig", Map.of("thinkingLevel", cfg.nivelRaciocinio()));
            generationConfig.put("responseMimeType", "application/json");
            generationConfig.put("responseJsonSchema", ESQUEMA_RESPOSTA);
        }
        Map<String, Object> corpo = Map.of("contents", List.of(Map.of("parts", parts)), "generationConfig", generationConfig);

        Resposta resposta;
        long inicio = System.currentTimeMillis();
        try {
            resposta = http.post()
                    .uri("/models/{modelo}:generateContent", modelo)
                    .header("x-goog-api-key", cfg.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(json.writeValueAsString(corpo))
                    .exchange((req, res) -> new Resposta(res.getStatusCode().value(),
                            new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8)));
        } catch (ResourceAccessException e) {
            if (e.getCause() instanceof SocketTimeoutException) {
                log.warn("[Gemini] {} não respondeu em {} s", modelo, cfg.timeout().toSeconds());
                throw FalhaIa.sobrecarga("A IA não respondeu em " + cfg.timeout().toSeconds() + " s (" + modelo + ")", 0, e);
            }
            throw new FalhaIa("Falha de rede ao chamar a API Gemini: " + e.getMessage(), 0, true, e);
        }
        long ms = System.currentTimeMillis() - inicio;

        if (resposta.status() >= 400) {
            log.info("[Gemini] {} → HTTP {} em {} ms", modelo, resposta.status(), ms);
            throw erroHttp(modelo, resposta);
        }
        return extrairTexto(modelo, resposta.corpo(), ms);
    }

    private FalhaIa erroHttp(String modelo, Resposta r) {
        String detalhe = "HTTP " + r.status();
        boolean porDia = false;
        try {
            CorpoErro erro = json.readValue(r.corpo(), CorpoErro.class);
            if (erro.error() != null && erro.error().message() != null) detalhe = erro.error().message();
            porDia = erro.error() != null && erro.error().violouCotaDiaria();
        } catch (JacksonException e) {
            if (!r.corpo().isBlank()) detalhe = r.corpo();
        }
        log.warn("[Gemini] {} respondeu {}: {}", modelo, r.status(), detalhe);

        if (r.status() == 404 && detalhe.toLowerCase().contains("model")) {
            Matcher m = MODELO_SUGERIDO.matcher(detalhe);
            String sugerido = null;
            while (m.find()) sugerido = m.group(1);
            detalhe = "o modelo \"" + modelo + "\" não está disponível para esta chave. Ajuste helpagent.gemini.modelo-primario/"
                    + "modelo-fallback" + (sugerido != null ? " (o Google sugeriu " + sugerido + ")." : ".");
        }
        String msg = "API Gemini (" + r.status() + "): " + detalhe;
        if (r.status() == 503) return FalhaIa.sobrecarga(msg, 503, null);
        if (r.status() == 429 && porDia) return FalhaIa.cotaDiaria(msg);
        return new FalhaIa(msg, r.status(), STATUS_RETENTAVEL.contains(r.status()));
    }

    private String extrairTexto(String modelo, String corpo, long ms) {
        RespostaGemini r = json.readValue(corpo, RespostaGemini.class);
        // Medição: é daqui que sai o diagnóstico de lentidão (entrada grande = imagem/PDF pesado; raciocínio alto = thinkingLevel).
        Uso u = r.usageMetadata();
        log.info("[Gemini] {} → 200 em {} ms · tokens entrada={} saída={} raciocínio={}", modelo, ms,
                u == null ? "?" : u.promptTokenCount(), u == null ? "?" : u.candidatesTokenCount(),
                u == null ? "?" : u.thoughtsTokenCount());
        StringBuilder texto = new StringBuilder();
        Candidato primeiro = r.candidates() == null || r.candidates().isEmpty() ? null : r.candidates().getFirst();
        if (primeiro != null && primeiro.content() != null && primeiro.content().parts() != null) {
            primeiro.content().parts().forEach(p -> { if (p.text() != null) texto.append(p.text()); });
        }
        if (texto.isEmpty()) {
            String finish = primeiro != null && primeiro.finishReason() != null ? primeiro.finishReason() : "desconhecido";
            String bloqueio = r.promptFeedback() != null ? r.promptFeedback().blockReason() : null;
            // Bloqueio por política se repete sempre; resposta vazia por outro motivo, não.
            throw new FalhaIa("Resposta vazia da IA. finishReason=" + finish
                    + (bloqueio != null ? ", bloqueado: " + bloqueio : ""), 200, bloqueio == null);
        }
        return texto.toString();
    }

    private record Resposta(int status, String corpo) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RespostaGemini(List<Candidato> candidates, PromptFeedback promptFeedback, Uso usageMetadata) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Uso(Integer promptTokenCount, Integer candidatesTokenCount, Integer thoughtsTokenCount) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Candidato(Conteudo content, String finishReason) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Conteudo(List<Parte> parts) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Parte(String text) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PromptFeedback(String blockReason) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CorpoErro(DetalheErro error) {
    }

    /** Corpo de erro do Google: {@code details[].violations[].quotaId} diz qual cota estourou. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record DetalheErro(String message, List<Extra> details) {
        boolean violouCotaDiaria() {
            return details != null && details.stream()
                    .filter(d -> d.violations() != null)
                    .flatMap(d -> d.violations().stream())
                    .anyMatch(v -> v.quotaId() != null && v.quotaId().contains("PerDay"));
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Extra(List<Violacao> violations) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Violacao(String quotaId) {
    }
}
