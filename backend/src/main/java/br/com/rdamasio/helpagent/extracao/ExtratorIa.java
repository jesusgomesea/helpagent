package br.com.rdamasio.helpagent.extracao;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import br.com.rdamasio.helpagent.common.Documento;
import br.com.rdamasio.helpagent.config.HelpAgentProperties;

/**
 * Política de resiliência da extração. Parte da lógica do HTML v3.5 ({@code chamarGeminiComRetry/Fallback}),
 * ajustada pelo que se mediu em uso real: uma leitura normal leva 5–10 s, mas o modelo principal sobrecarregado
 * chegou a segurar 37 s antes de devolver 503 — e insistir nele custava mais uma espera dessas.
 *
 * <ul>
 *   <li><b>Sobrecarga</b> (503 ou tempo esgotado) no principal → vai direto para o modelo de fallback,
 *       sem repetir o principal (capacidade é por modelo; o alternativo costuma estar livre).</li>
 *   <li><b>Quota diária</b> esgotada no principal → fallback já, sem novas tentativas, e o principal fica
 *       fora por {@link #PAUSA_COTA} antes de ser sondado de novo. Não esperamos a virada do dia: medido em
 *       25/09/2026, o principal voltou ~25 min depois do 429 "PerDay" (a cota gratuita libera aos poucos).
 *       Sondar é barato — o 429 volta na hora, ao contrário do 503.</li>
 *   <li>Erro transitório (429 por minuto, 5xx, rede, JSON malformado) → repete o mesmo modelo até
 *       {@code maxTentativas}, com espera crescente.</li>
 *   <li>Erro definitivo (400/401/403, bloqueio por política) → não repete.</li>
 *   <li>400 por campo de config recusado → repete uma vez com a config mínima.</li>
 *   <li><b>Reserva em paralelo</b>: se o principal passar de {@code reservaApos} (12 s) sem responder, o fallback
 *       é disparado junto e vale a primeira resposta boa. Corta a cauda de espera (37 s → ~12 + 5 s) e só gasta
 *       uma requisição extra quando a chamada já está lenta. A chamada perdedora termina em segundo plano e é
 *       descartada.</li>
 * </ul>
 */
@Component
public class ExtratorIa implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(ExtratorIa.class);
    private static final Pattern CONFIG_RECUSADA = Pattern.compile(
            "thinking|responseMimeType|response_mime_type|responseJsonSchema|response_json_schema|Unknown name"
                    + "|Invalid JSON payload|generation_?config",
            Pattern.CASE_INSENSITIVE);
    /**
     * Reserva para erro sem o campo estruturado quotaId: só "per day" no texto. "quota"/"free_tier" NÃO servem —
     * aparecem também no 429 por minuto, que se resolve esperando alguns segundos, não trocando de modelo o dia todo.
     */
    private static final Pattern QUOTA_DIARIA = Pattern.compile("per.?day", Pattern.CASE_INSENSITIVE);
    /**
     * Depois disso não se começa nova tentativa: a tela devolve o erro e o atendente pode preencher à mão.
     * Com timeout de 30 s por chamada, o pior caso fica em torno de 90 s em vez de crescer sem limite.
     */
    static final java.time.Duration PRAZO_TOTAL = java.time.Duration.ofSeconds(60);

    /** Quanto tempo o principal fica fora depois de estourar a cota diária, antes de ser sondado de novo. */
    static final java.time.Duration PAUSA_COTA = java.time.Duration.ofMinutes(15);

    /** @param tentativas chamadas feitas à API, somando os dois modelos */
    public record Resultado(DadosExtraidos dados, String modelo, int tentativas) {
    }

    private final GeminiClient cliente;
    private final RespostaIaParser parser;
    private final HelpAgentProperties.Gemini cfg;
    private final Clock relogio;

    /** Até quando o principal fica fora por cota diária estourada (null = disponível). */
    private volatile Instant principalEsgotadoAte;
    /** Threads virtuais: cada chamada passa quase todo o tempo esperando rede. */
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    @Autowired
    public ExtratorIa(GeminiClient cliente, RespostaIaParser parser, HelpAgentProperties props) {
        this(cliente, parser, props, Clock.systemUTC());
    }

    ExtratorIa(GeminiClient cliente, RespostaIaParser parser, HelpAgentProperties props, Clock relogio) {
        this.cliente = cliente;
        this.parser = parser;
        this.cfg = props.gemini();
        this.relogio = relogio;
    }

    public Resultado extrair(List<Documento> documentos, String prompt) {
        if (cfg.modeloForcado() != null && !cfg.modeloForcado().isBlank()) {
            return comRetry(cfg.modeloForcado(), documentos, prompt, false, 0);
        }
        if (!principalDisponivel()) {
            return comRetry(cfg.modeloFallback(), documentos, prompt, false, 0);
        }

        CompletableFuture<Resultado> principal =
                CompletableFuture.supplyAsync(() -> tentarPrincipal(documentos, prompt), executor);
        long espera = cfg.reservaApos() == null ? 0 : cfg.reservaApos().toMillis();
        try {
            return espera > 0 ? principal.get(espera, TimeUnit.MILLISECONDS) : principal.get();
        } catch (TimeoutException lento) {
            log.info("[Gemini] {} passou de {} ms sem responder; disparando {} em paralelo.", cfg.modeloPrimario(),
                    espera, cfg.modeloFallback());
            CompletableFuture<Resultado> reserva = CompletableFuture.supplyAsync(
                    () -> comRetry(cfg.modeloFallback(), documentos, prompt, false, 0), executor);
            return primeiraBoa(principal, reserva);
        } catch (ExecutionException e) {
            // Principal falhou antes do prazo: só sobrecarga/quota justificam o fallback (o resto é definitivo).
            FalhaIa f = falha(e);
            if (!f.sobrecarga() && !quotaDiaria(f)) throw f;
            return comRetry(cfg.modeloFallback(), documentos, prompt, false, f.tentativas(), true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FalhaIa("Extração interrompida", 0, false, e);
        }
    }

    /** Principal com a política de desistir cedo; registra a quota esgotada para as próximas extrações do dia. */
    private Resultado tentarPrincipal(List<Documento> documentos, String prompt) {
        try {
            return comRetry(cfg.modeloPrimario(), documentos, prompt, true, 0);
        } catch (FalhaIa e) {
            if (quotaDiaria(e)) {
                principalEsgotadoAte = relogio.instant().plus(PAUSA_COTA);
                log.warn("[Gemini] Quota diária do {} esgotada até {}. Usando {}.", cfg.modeloPrimario(),
                        principalEsgotadoAte, cfg.modeloFallback());
            } else if (e.sobrecarga()) {
                log.warn("[Gemini] {} sobrecarregado ({}). Usando {}.", cfg.modeloPrimario(), e.getMessage(),
                        cfg.modeloFallback());
            }
            throw e;
        }
    }

    /** Devolve a primeira das duas chamadas que terminar com sucesso; se as duas falharem, o erro da reserva. */
    private Resultado primeiraBoa(CompletableFuture<Resultado> principal, CompletableFuture<Resultado> reserva) {
        while (true) {
            if (terminouBem(principal)) return principal.join();
            if (terminouBem(reserva)) return reserva.join();
            boolean principalFalhou = principal.isCompletedExceptionally();
            boolean reservaFalhou = reserva.isCompletedExceptionally();
            if (principalFalhou && reservaFalhou) throw falha(reserva);
            CompletableFuture<?> aguardar = principalFalhou ? reserva
                    : reservaFalhou ? principal : CompletableFuture.anyOf(principal, reserva);
            try {
                aguardar.get();
            } catch (ExecutionException ignorada) {
                // quem falhou é tratado na próxima volta do laço
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new FalhaIa("Extração interrompida", 0, false, e);
            }
        }
    }

    private static boolean terminouBem(CompletableFuture<?> f) {
        return f.isDone() && !f.isCompletedExceptionally();
    }

    private static FalhaIa falha(CompletableFuture<?> f) {
        try {
            f.join();
            throw new IllegalStateException("chamada terminou sem falha");
        } catch (java.util.concurrent.CompletionException e) {
            return comoFalhaIa(e.getCause());
        }
    }

    private static FalhaIa falha(ExecutionException e) {
        return comoFalhaIa(e.getCause());
    }

    private static FalhaIa comoFalhaIa(Throwable t) {
        return t instanceof FalhaIa f ? f : new FalhaIa("Erro inesperado na extração: " + t, 0, false, t);
    }

    @Override
    public void destroy() {
        executor.shutdownNow();
    }

    private Resultado comRetry(String modelo, List<Documento> documentos, String prompt,
            boolean desistirNaSobrecarga, int jaFeitas) {
        return comRetry(modelo, documentos, prompt, desistirNaSobrecarga, jaFeitas, false);
    }

    /**
     * @param desistirNaSobrecarga no principal, sobrecarga e quota diária encerram na hora (quem resolve é o fallback)
     * @param jaFeitas             tentativas já gastas no modelo anterior, para o total reportado
     * @param alternarNaSobrecarga no fallback depois de um 503 do principal: se o fallback também travar, a próxima
     *                             tentativa volta ao principal em vez de insistir no mesmo modelo. Medido em 28/09/2026:
     *                             o principal devolveu 503 e o fallback estourou 30 s três vezes seguidas (95 s até o
     *                             erro), enquanto o principal, logo depois, respondia em 2–3 s. Sobrecarga é por modelo e
     *                             passageira; alternar aproveita qual dos dois estiver livre no momento.
     */
    private Resultado comRetry(String modelo, List<Documento> documentos, String prompt,
            boolean desistirNaSobrecarga, int jaFeitas, boolean alternarNaSobrecarga) {
        FalhaIa ultima = null;
        boolean configMinima = false;
        long limite = System.nanoTime() + PRAZO_TOTAL.toNanos();
        String atual = modelo;
        int tentativa = 1;
        for (; tentativa <= cfg.maxTentativas(); tentativa++) {
            try {
                String texto = cliente.gerar(atual, documentos, prompt, configMinima);
                return new Resultado(parser.parse(texto), atual, jaFeitas + tentativa);
            } catch (FalhaIa e) {
                ultima = e;
                if (e.status() == 400 && !configMinima && CONFIG_RECUSADA.matcher(e.getMessage()).find()) {
                    log.warn("[Gemini] generationConfig otimizado recusado — repetindo com configuração mínima.");
                    configMinima = true;
                    continue;
                }
                if (desistirNaSobrecarga && (e.sobrecarga() || quotaDiaria(e))) break;
                if (quotaDiaria(e) || !e.retentavel() || tentativa == cfg.maxTentativas()) break;
                // Prazo total: melhor devolver o erro (o atendente pode preencher à mão) do que segurar a tela.
                if (System.nanoTime() > limite) {
                    log.warn("[Gemini] prazo de {} s da extração estourado; desistindo.", PRAZO_TOTAL.toSeconds());
                    break;
                }
                if (e.sobrecarga() && alternarNaSobrecarga) {
                    String outro = outroModelo(atual);
                    if (!outro.equals(atual)) {
                        log.warn("[Gemini] {} também sobrecarregado; alternando para {}.", atual, outro);
                        atual = outro;
                        continue; // troca de modelo não precisa de espera: a sobrecarga é do outro
                    }
                }
                esperar(tentativa);
            }
        }
        // Registra quantas tentativas REALMENTE aconteceram (o erro definitivo sai na primeira).
        throw ultima.comTentativas(jaFeitas + Math.min(tentativa, cfg.maxTentativas()));
    }

    /** Principal ↔ fallback; o principal só entra na alternância se não estiver fora por cota. */
    private String outroModelo(String atual) {
        if (atual.equals(cfg.modeloFallback())) return principalDisponivel() ? cfg.modeloPrimario() : atual;
        return cfg.modeloFallback();
    }

    private boolean principalDisponivel() {
        Instant ate = principalEsgotadoAte;
        return ate == null || relogio.instant().isAfter(ate);
    }

    private static boolean quotaDiaria(FalhaIa e) {
        return e.cotaDiaria() || (e.status() == 429 && QUOTA_DIARIA.matcher(e.getMessage()).find());
    }

    private void esperar(int tentativa) {
        try {
            Thread.sleep(cfg.backoff().multipliedBy(tentativa));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FalhaIa("Extração interrompida", 0, false, e);
        }
    }
}
