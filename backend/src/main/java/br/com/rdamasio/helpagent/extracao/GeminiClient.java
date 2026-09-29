package br.com.rdamasio.helpagent.extracao;

import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

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
 * a política de tentativas e a cadeia de modelos ficam no {@link ExtratorIa}.
 *
 * <p>A chave vai no cabeçalho {@code x-goog-api-key}, nunca na URL, e só existe no servidor.
 *
 * <p>Devolve também os tokens gastos ({@link Geracao}), que o controle de cota usa. No erro 429 lê do corpo
 * qual cota estourou (dia ou minuto), o limite ({@code quotaValue}) e o "tente de novo em" ({@code retryDelay}).
 */
@Component
public class GeminiClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiClient.class);
    private static final List<Integer> STATUS_RETENTAVEL = List.of(408, 425, 429, 500, 502, 503, 504);
    private static final Pattern MODELO_SUGERIDO = Pattern.compile("models/(gemini[\\w.\\-]+)");
    /** 429 sem o campo estruturado: só "per day" no texto indica a cota diária ("quota" aparece nos dois). */
    private static final Pattern QUOTA_DIARIA_TEXTO = Pattern.compile("per.?day", Pattern.CASE_INSENSITIVE);

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
        fabrica.setConnectTimeout(Duration.ofSeconds(15));
        fabrica.setReadTimeout(cfg.timeout());
        this.http = RestClient.builder().baseUrl(cfg.urlBase()).requestFactory(fabrica).build();
    }

    public boolean configurado() {
        return cfg.apiKey() != null && !cfg.apiKey().isBlank();
    }

    /**
     * Resultado de uma chamada: o texto gerado (esperado: JSON) e o que o Google contou de tokens.
     *
     * @param tokensEntrada o que conta para o limite de tokens por minuto (prompt + arquivos)
     */
    public record Geracao(String texto, Integer tokensEntrada, Integer tokensSaida, Integer tokensRaciocinio, long ms) {
    }

    /**
     * Modelos que esta chave enxerga para {@code generateContent} (sem o prefixo "models/"). Não gasta cota.
     * Usado na subida para tirar da cadeia um modelo que não existe, antes de um orçamento esbarrar nele.
     */
    public List<String> modelosDisponiveis() {
        List<String> nomes = new ArrayList<>();
        String pagina = null;
        do {
            String token = pagina;
            ListaModelos lista = http.get()
                    .uri(u -> {
                        u.path("/models").queryParam("pageSize", 1000);
                        if (token != null) u.queryParam("pageToken", token);
                        return u.build();
                    })
                    .header("x-goog-api-key", cfg.apiKey())
                    .retrieve()
                    .body(ListaModelos.class);
            if (lista == null || lista.models() == null) break;
            for (ModeloApi m : lista.models()) {
                if (m.name() != null && m.supportedGenerationMethods() != null
                        && m.supportedGenerationMethods().contains("generateContent")) {
                    nomes.add(m.name().replaceFirst("^models/", ""));
                }
            }
            pagina = lista.nextPageToken();
        } while (pagina != null && !pagina.isBlank());
        return nomes;
    }

    /**
     * @param configMinima sem {@code thinkingConfig}/{@code responseMimeType}/{@code responseJsonSchema}, para quando
     *                     a API recusa algum desses campos (o {@link ExtratorIa} repete assim uma vez)
     */
    public Geracao gerar(String modelo, List<Documento> documentos, String prompt, boolean configMinima) {
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
            generationConfig.put("thinkingConfig", configRaciocinio(modelo, cfg.nivelRaciocinio()));
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

    /**
     * O Gemini 3 aceita {@code thinkingLevel}; o 2.x só {@code thinkingBudget} (em tokens) e devolve 400 para o outro
     * campo — o que custaria uma requisição a mais toda vez que a cadeia descesse até ele.
     */
    static Map<String, Object> configRaciocinio(String modelo, String nivel) {
        if (!modelo.startsWith("gemini-2")) return Map.of("thinkingLevel", nivel);
        int orcamento = switch (nivel) {
            case "medium" -> 2048;
            case "high" -> -1; // dinâmico: o modelo decide
            default -> 0;      // minimal/low: sem raciocínio, como o 3.x já faz em low (raciocínio=null medido)
        };
        return Map.of("thinkingBudget", orcamento);
    }

    private FalhaIa erroHttp(String modelo, Resposta r) {
        String detalhe = "HTTP " + r.status();
        boolean porDia = false;
        Integer limite = null;
        Duration tentarDepois = null;
        try {
            CorpoErro erro = json.readValue(r.corpo(), CorpoErro.class);
            if (erro.error() != null && erro.error().message() != null) detalhe = erro.error().message();
            if (erro.error() != null) {
                porDia = erro.error().violouCotaDiaria();
                limite = erro.error().limite();
                tentarDepois = erro.error().tentarDepois();
            }
        } catch (JacksonException e) {
            if (!r.corpo().isBlank()) detalhe = r.corpo();
        }
        log.warn("[Gemini] {} respondeu {}: {}", modelo, r.status(), detalhe);

        if (r.status() == 404 && detalhe.toLowerCase().contains("model")) {
            Matcher m = MODELO_SUGERIDO.matcher(detalhe);
            String sugerido = null;
            while (m.find()) sugerido = m.group(1);
            detalhe = "o modelo \"" + modelo + "\" não está disponível para esta chave. Ajuste helpagent.gemini.cadeia"
                    + (sugerido != null ? " (o Google sugeriu " + sugerido + ")." : ".");
            return FalhaIa.modeloIndisponivel("API Gemini (404): " + detalhe);
        }
        String msg = "API Gemini (" + r.status() + "): " + detalhe;
        if (r.status() == 503) return FalhaIa.sobrecarga(msg, 503, null);
        if (r.status() == 429 && (porDia || QUOTA_DIARIA_TEXTO.matcher(detalhe).find())) {
            return FalhaIa.cotaDiaria(msg).comLimiteInformado(limite, tentarDepois);
        }
        if (r.status() == 429) return FalhaIa.cotaPorMinuto(msg, tentarDepois).comLimiteInformado(limite, tentarDepois);
        return new FalhaIa(msg, r.status(), STATUS_RETENTAVEL.contains(r.status()));
    }

    private Geracao extrairTexto(String modelo, String corpo, long ms) {
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
        return new Geracao(texto.toString(), u == null ? null : u.promptTokenCount(),
                u == null ? null : u.candidatesTokenCount(), u == null ? null : u.thoughtsTokenCount(), ms);
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

    /**
     * Corpo de erro do Google: {@code details[]} traz um QuotaFailure ({@code violations[].quotaId} diz qual cota
     * estourou, {@code quotaValue} o limite) e um RetryInfo ({@code retryDelay}, ex.: "7.58s").
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record DetalheErro(String message, List<Extra> details) {
        boolean violouCotaDiaria() {
            return violacoes().anyMatch(v -> v.quotaId() != null && v.quotaId().contains("PerDay"));
        }

        Integer limite() {
            return violacoes().map(Violacao::quotaValue).filter(Objects::nonNull)
                    .map(v -> String.valueOf(v).replaceAll("\\D", "")).filter(v -> !v.isEmpty() && v.length() < 10)
                    .map(Integer::valueOf).findFirst().orElse(null);
        }

        Duration tentarDepois() {
            if (details == null) return null;
            return details.stream().map(Extra::retryDelay).filter(d -> d != null && d.matches("[\\d.]+s"))
                    .map(d -> Duration.ofMillis((long) (Double.parseDouble(d.substring(0, d.length() - 1)) * 1000)))
                    .findFirst().orElse(null);
        }

        private Stream<Violacao> violacoes() {
            return details == null ? Stream.empty()
                    : details.stream().filter(d -> d.violations() != null).flatMap(d -> d.violations().stream());
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Extra(List<Violacao> violations, String retryDelay) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Violacao(String quotaId, Object quotaValue) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ListaModelos(List<ModeloApi> models, String nextPageToken) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ModeloApi(String name, List<String> supportedGenerationMethods) {
    }
}
