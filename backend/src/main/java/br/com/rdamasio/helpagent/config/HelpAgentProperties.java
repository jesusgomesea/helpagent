package br.com.rdamasio.helpagent.config;

import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Tudo que no HTML v3.5 era constante no script (modelos, limites, nomes padrão)
 * e agora muda por configuração, sem novo deploy do frontend.
 */
@ConfigurationProperties("helpagent")
public record HelpAgentProperties(
        Gemini gemini,
        Orcamento orcamento,
        Armazenamento armazenamento,
        Seguranca seguranca,
        Cotacao cotacao,
        @DefaultValue("America/Sao_Paulo") ZoneId fuso,
        @DefaultValue("http://localhost:4200") List<String> corsOrigens) {

    /**
     * @param cadeia          modelos do melhor para o pior (docs/MANUTENCAO.md §4). A leitura usa o primeiro com
     *                        cota; esgotou ou sobrecarregou, desce um degrau. Os limites de cada um são os do painel
     *                        do AI Studio (o Google não os publica nem devolve pela API)
     * @param modeloForcado   quando preenchido, pula a cadeia (útil para comparar modelos)
     * @param maxTentativas   tentativas no MESMO modelo para erro transitório (500, rede, JSON quebrado)
     * @param backoff         espera base entre tentativas; a n-ésima espera {@code n * backoff}
     * @param timeout         espera máxima por uma chamada. Leitura normal leva 5–10 s; o Google, sobrecarregado,
     *                        chegou a segurar 37 s antes de devolver 503 — passar disso é esperar em vão
     * @param nivelRaciocinio thinkingLevel do Gemini 3 ("minimal", "low", "medium", "high"); menor = mais rápido.
     *                        Nos modelos 2.x vira thinkingBudget (ver GeminiClient)
     * @param cacheExtracao   por quanto tempo reaproveitar a leitura dos mesmos arquivos (0 desliga)
     * @param reservaApos     se o modelo da vez passar deste tempo sem responder, dispara o degrau de baixo em
     *                        paralelo e fica com a primeira resposta (0 desliga). Só se o degrau de baixo tiver cota
     * @param maxSimultaneas  leituras ao mesmo tempo no servidor; a próxima espera vaga (evita rajada de RPM)
     * @param esperaVaga      quanto a leitura espera por vaga antes de desistir com "IA ocupada"
     * @param maxChamadasPorLeitura teto de requisições que UMA leitura pode gastar, somando degraus, repetições e
     *                        reserva. Antes eram até 6 numa leitura ruim
     */
    public record Gemini(
            String apiKey,
            @DefaultValue("https://generativelanguage.googleapis.com/v1beta") String urlBase,
            List<ModeloIa> cadeia,
            @DefaultValue("") String modeloForcado,
            @DefaultValue("3") int maxTentativas,
            @DefaultValue("1200ms") Duration backoff,
            @DefaultValue("32768") int maxOutputTokens,
            @DefaultValue("30s") Duration timeout,
            @DefaultValue("low") String nivelRaciocinio,
            @DefaultValue("30m") Duration cacheExtracao,
            @DefaultValue("12s") Duration reservaApos,
            @DefaultValue("3") int maxSimultaneas,
            @DefaultValue("45s") Duration esperaVaga,
            @DefaultValue("4") int maxChamadasPorLeitura) {

        public Gemini {
            cadeia = cadeia == null ? List.of() : List.copyOf(cadeia);
        }
    }

    /**
     * Um degrau da cadeia e os limites do plano para ele. O servidor não passa deles por conta própria (desce de
     * degrau sem chamar), o que evita o 429 — que também conta como requisição e piora o RPM.
     *
     * @param rpm requisições por minuto
     * @param tpm tokens de ENTRADA por minuto (é o que o Google limita)
     * @param rpd requisições por dia (o dia do Google vira à meia-noite do Pacífico)
     */
    public record ModeloIa(
            String modelo,
            @DefaultValue("5") int rpm,
            @DefaultValue("250000") int tpm,
            @DefaultValue("20") int rpd) {
    }

    /**
     * @param maxItens limite físico do impresso (10 linhas no template)
     */
    public record Orcamento(
            @DefaultValue("10") int maxItens,
            @DefaultValue("TECNOLOGIA") String departamento,
            @DefaultValue("6%") String variacao,
            @DefaultValue("ANTONIO FELIPE") String requerentePadrao,
            @DefaultValue("GILSON PASSOS") String gestorPadrao) {
    }

    public record Armazenamento(@DefaultValue("./dados/arquivos") Path diretorio) {
    }

    /**
     * Cotação no Mercado Livre (pacote {@code cotacao}; ver docs/MANUTENCAO.md §7).
     *
     * @param canal          navegador instalado que o Playwright dirige: "chrome" ou "msedge". Não baixamos
     *                       navegador próprio — o site bloqueia o Chromium do Playwright em modo headless
     * @param perfil         pasta do perfil persistente. Guarda cookies (inclusive o de "verificação de conta"
     *                       resolvida à mão) — por isso fica em dados/, fora do versionamento
     * @param maxPaginas     teto de páginas de busca por cotação (~50 anúncios cada)
     * @param cacheResultado quanto tempo o último resultado de cada termo fica disponível para baixar a planilha
     * @param janelaVisivel  true mostra a janela do Chrome na tela — só para diagnosticar (MANUTENCAO §7)
     * @param prints         pasta dos prints das páginas de produto (orçamento por cotação), fora do git
     * @param validadePrints quanto tempo um print fica guardado esperando virar orçamento. Uma cesta costuma ser
     *                       montada ao longo do dia; depois de gerado, o print já está dentro do PDF
     */
    public record Cotacao(
            @DefaultValue("chrome") String canal,
            @DefaultValue("./dados/navegador") Path perfil,
            @DefaultValue("3") int maxPaginas,
            @DefaultValue("30m") Duration cacheResultado,
            @DefaultValue("false") boolean janelaVisivel,
            @DefaultValue("./dados/prints") Path prints,
            @DefaultValue("7d") Duration validadePrints) {
    }

    /**
     * Desligada só em desenvolvimento local. Em produção a API exige JWT do provedor corporativo.
     */
    public record Seguranca(@DefaultValue("true") boolean habilitada) {
    }
}
