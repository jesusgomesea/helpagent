package br.com.rdamasio.helpagent.extracao;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import br.com.rdamasio.helpagent.common.Documento;
import br.com.rdamasio.helpagent.config.HelpAgentProperties;
import br.com.rdamasio.helpagent.usoia.ControleCotaIa;
import br.com.rdamasio.helpagent.usoia.Ocorrencia;
import br.com.rdamasio.helpagent.usoia.Papel;
import br.com.rdamasio.helpagent.usoia.UsoIa;
import br.com.rdamasio.helpagent.usoia.UsoIaService;

/**
 * Política de resiliência da extração: percorre a <b>cadeia de modelos</b> ({@code helpagent.gemini.cadeia}, do
 * melhor para o pior) respeitando a cota de cada um ({@link ControleCotaIa}). Nasceu da lógica do HTML v3.5
 * ({@code chamarGeminiComRetry/Fallback}) e foi ajustada pelo que se mediu em uso real: uma leitura normal leva
 * 5–10 s, mas um modelo sobrecarregado chegou a segurar 37 s antes de devolver 503.
 *
 * <ul>
 *   <li><b>Escolha do modelo</b>: o primeiro da cadeia que tem vez (cota do minuto e do dia, nas contas do servidor
 *       e nas respostas do Google). Sem vez → desce um degrau <b>sem chamar</b> (o 429 também conta requisição).</li>
 *   <li><b>Sobrecarga</b> (503 ou tempo esgotado) → desce um degrau na hora, sem repetir o mesmo; o modelo "esfria"
 *       60 s e as próximas leituras preferem o de baixo. Se todos já falharam nesta leitura, volta ao melhor que
 *       ainda tem vez (medido em 28/09/2026: o principal deu 503 e, logo depois, respondia em 2–3 s).</li>
 *   <li><b>Cota do minuto</b> (429) → desce e o modelo descansa o que o Google pediu. Antes o sistema repetia o mesmo
 *       modelo 1,2 s depois e levava outro 429: era assim que uma rajada se alimentava.</li>
 *   <li><b>Cota do dia</b> (429) → desce e o modelo fica 15 min fora; depois é sondado de novo e, respondendo, volta
 *       ao topo sozinho.</li>
 *   <li><b>404</b> (modelo não existe para a chave) → desce e o modelo sai da cadeia até reiniciar.</li>
 *   <li>Erro transitório (500, rede, JSON malformado) → repete o MESMO modelo até {@code maxTentativas}, com espera
 *       crescente; persistiu, desce.</li>
 *   <li>Erro definitivo (400/401/403, bloqueio por política) → não repete nem desce.</li>
 *   <li>400 por campo de config recusado → repete uma vez com a config mínima.</li>
 *   <li><b>Reserva em paralelo</b>: se o modelo da vez passar de {@code reservaApos} (12 s) sem responder e o degrau
 *       de baixo tiver cota, ele é disparado junto e vale a primeira resposta boa.</li>
 *   <li><b>Anti-rajada</b>: no máximo {@code maxSimultaneas} leituras ao mesmo tempo (a próxima espera vaga) e no
 *       máximo {@code maxChamadasPorLeitura} requisições por leitura, somando tudo acima.</li>
 * </ul>
 * Toda requisição enviada vai para a tabela {@code uso_ia} ({@link UsoIaService}), com tokens, papel e resultado.
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
     * aparecem também no 429 por minuto, que se resolve esperando alguns segundos, não tirando o modelo por 15 min.
     */
    private static final Pattern QUOTA_DIARIA = Pattern.compile("per.?day", Pattern.CASE_INSENSITIVE);
    /**
     * Depois disso não se começa nova tentativa: a tela devolve o erro e o atendente pode preencher à mão.
     * Com timeout de 30 s por chamada, o pior caso fica em torno de 90 s em vez de crescer sem limite.
     */
    static final java.time.Duration PRAZO_TOTAL = java.time.Duration.ofSeconds(60);

    /**
     * Estimativa de tokens de entrada antes de chamar (o controle reserva isso no limite por minuto; depois troca
     * pelo que o Google contou). Medido no log de 25–28/09/2026: 1 arquivo ≈ 2.050–2.850, 3 arquivos ≈ 4.500 — o
     * prompt pesa ~1.000 e cada imagem ou página de PDF, ~1.100.
     */
    static final int TOKENS_POR_ARQUIVO = 1_100;
    private static final Pattern PAGINA_PDF = Pattern.compile("/Type\\s*/Page(?![a-zA-Z])");

    /**
     * @param tentativas chamadas feitas à API nesta leitura, somando todos os modelos
     * @param degrau     posição do modelo que respondeu na cadeia: 0 = o melhor; -1 = modelo forçado
     * @param leitura    id desta leitura (o mesmo das linhas de uso_ia): liga o que a IA leu ao orçamento gerado
     */
    public record Resultado(DadosExtraidos dados, String modelo, int tentativas, int degrau, String leitura) {
    }

    /** Quem pediu a leitura; vai para o registro de uso. */
    public record Contexto(String modo, String origem) {
        public static final Contexto VAZIO = new Contexto(null, null);
    }

    private final GeminiClient cliente;
    private final RespostaIaParser parser;
    private final HelpAgentProperties.Gemini cfg;
    private final ControleCotaIa controle;
    private final UsoIaService uso;
    private final Semaphore vagas;
    /** Threads virtuais: cada chamada passa quase todo o tempo esperando rede. */
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public ExtratorIa(GeminiClient cliente, RespostaIaParser parser, HelpAgentProperties props, ControleCotaIa controle,
            UsoIaService uso) {
        this.cliente = cliente;
        this.parser = parser;
        this.cfg = props.gemini();
        this.controle = controle;
        this.uso = uso;
        this.vagas = new Semaphore(Math.max(1, cfg.maxSimultaneas()), true);
        if (controle.cadeia().isEmpty() && semForcado()) {
            throw new IllegalStateException("helpagent.gemini.cadeia está vazia: configure ao menos um modelo "
                    + "(docs/MANUTENCAO.md §4) ou helpagent.gemini.modelo-forcado.");
        }
    }

    /**
     * Na subida, confere quais modelos da cadeia a chave enxerga (listar modelos não gasta cota) e tira da cadeia
     * os que não existem — melhor descobrir agora do que na hora de ler um orçamento.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void conferirCadeia() {
        if (!cliente.configurado()) return;
        executor.submit(() -> {
            try {
                List<String> existentes = cliente.modelosDisponiveis();
                for (String m : controle.cadeia()) {
                    if (!existentes.contains(m)) controle.marcarIndisponivel(m, "a chave não enxerga este modelo na API");
                }
                log.info("[Gemini] cadeia de modelos: {}", String.join(" → ", controle.cadeia()));
            } catch (RuntimeException e) {
                log.warn("[Gemini] não consegui listar os modelos da chave ({}); a cadeia segue como configurada.",
                        e.getMessage());
            }
        });
    }

    public Resultado extrair(List<Documento> documentos, String prompt) {
        return extrair(documentos, prompt, Contexto.VAZIO);
    }

    public Resultado extrair(List<Documento> documentos, String prompt, Contexto contexto) {
        boolean vaga;
        try {
            vaga = vagas.tryAcquire(cfg.esperaVaga().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FalhaIa("Extração interrompida", 0, false, e);
        }
        if (!vaga) {
            throw new FalhaIa("A IA está ocupada com outras " + cfg.maxSimultaneas() + " leituras. Tente de novo em "
                    + "instantes ou preencha à mão.", 0, false);
        }
        try {
            return new Leitura(documentos, prompt, contexto).executar();
        } finally {
            vagas.release();
        }
    }

    private boolean semForcado() {
        return cfg.modeloForcado() == null || cfg.modeloForcado().isBlank();
    }

    /** Estado de UMA extração: quantas chamadas gastou, quais modelos já falharam nela, o último erro. */
    private final class Leitura {
        private final List<Documento> documentos;
        private final String prompt;
        private final Contexto contexto;
        private final int estimativa;
        private final String id = UUID.randomUUID().toString();
        private final AtomicInteger chamadas = new AtomicInteger();
        private final Map<String, Integer> falhas = new ConcurrentHashMap<>();
        private final long limite = System.nanoTime() + PRAZO_TOTAL.toNanos();
        private volatile boolean configMinima;
        private volatile FalhaIa ultima;
        /** Id da requisição (X-Request-Id) e demais chaves do log: as chamadas rodam em outras threads. */
        private final Map<String, String> mdc = MDC.getCopyOfContextMap();

        Leitura(List<Documento> documentos, String prompt, Contexto contexto) {
            this.documentos = documentos;
            this.prompt = prompt;
            this.contexto = contexto;
            this.estimativa = estimarTokens(documentos, prompt);
        }

        Resultado executar() {
            if (!semForcado()) return noModelo(cfg.modeloForcado(), Papel.PRINCIPAL);

            Papel papel = Papel.PRINCIPAL;
            // guarda contra laço: cada volta ou gasta uma chamada ou tira um modelo da vez
            for (int voltas = 0; voltas < controle.cadeia().size() * Math.max(1, cfg.maxChamadasPorLeitura()); voltas++) {
                if (!podeGastar() || prazoEstourado()) break;
                String m = escolher(Set.of());
                if (m == null) {
                    if (papel == Papel.PRINCIPAL) throw semModelo();
                    break;
                }
                if (papel == Papel.PRINCIPAL && controle.degrau(m) > 0) {
                    log.info("[Gemini] começando no degrau {} ({}): os de cima estão sem cota ou fora.", controle.degrau(m), m);
                }
                Resultado r = comReserva(m, papel);
                if (r != null) return r;
                papel = Papel.DEGRAU;
            }
            if (prazoEstourado()) log.warn("[Gemini] prazo de {} s da extração estourado; desistindo.", PRAZO_TOTAL.toSeconds());
            else if (!podeGastar()) log.warn("[Gemini] leitura chegou ao teto de {} chamadas; desistindo.", cfg.maxChamadasPorLeitura());
            throw (ultima != null ? ultima : semModelo()).comTentativas(chamadas.get());
        }

        /**
         * Um degrau: chama o modelo e, se ele passar de {@code reservaApos} sem responder, dispara o próximo com cota
         * em paralelo e fica com a primeira resposta boa. Vale em QUALQUER degrau, não só no primeiro: medido em
         * 29/09/2026, o 3.6 deu 503 em 2,7 s e a leitura, ao descer, esperou os 30 s inteiros do 3.5-flash travado.
         *
         * @return null = falhou por cota/sobrecarga/transitório (o laço desce); erro definitivo sai como exceção
         */
        private Resultado comReserva(String modelo, Papel papel) {
            CompletableFuture<Resultado> f1 = CompletableFuture.supplyAsync(comMdc(() -> noModelo(modelo, papel)), executor);
            long espera = cfg.reservaApos() == null ? 0 : cfg.reservaApos().toMillis();
            try {
                return espera > 0 ? f1.get(espera, TimeUnit.MILLISECONDS) : f1.get();
            } catch (TimeoutException lento) {
                String segundo = podeGastar() ? escolher(Set.of(modelo)) : null;
                if (segundo == null) return aguardar(f1);
                log.info("[Gemini] {} passou de {} ms sem responder; disparando {} em paralelo.", modelo, espera, segundo);
                CompletableFuture<Resultado> f2 = CompletableFuture.supplyAsync(comMdc(() -> noModelo(segundo, Papel.RESERVA)), executor);
                return primeiraBoa(f1, f2);
            } catch (ExecutionException e) {
                FalhaIa f = comoFalhaIa(e.getCause());
                if (definitiva(f)) throw f.comTentativas(chamadas.get());
                return null;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new FalhaIa("Extração interrompida", 0, false, e);
            }
        }

        /** Sem reserva possível: espera a chamada lenta; se ela falhar por cota/sobrecarga, devolve null (descer). */
        private Resultado aguardar(CompletableFuture<Resultado> f) {
            try {
                return f.get();
            } catch (ExecutionException e) {
                FalhaIa falha = comoFalhaIa(e.getCause());
                if (definitiva(falha)) throw falha.comTentativas(chamadas.get());
                return null;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new FalhaIa("Extração interrompida", 0, false, e);
            }
        }

        /**
         * O melhor modelo com vez agora, fora os excluídos. Ordem: quem falhou menos NESTA leitura, depois quem não
         * está esfriando, depois a posição na cadeia. Assim um 503 desce de degrau, mas se todos já falharam volta
         * ao melhor.
         */
        private String escolher(Set<String> excluir) {
            List<String> cadeia = controle.cadeia();
            return cadeia.stream()
                    .filter(m -> !excluir.contains(m) && controle.temVez(m, estimativa))
                    .min(Comparator.<String>comparingInt(m -> falhas.getOrDefault(m, 0))
                            .thenComparing(m -> controle.esfriando(m) ? 1 : 0)
                            .thenComparingInt(cadeia::indexOf))
                    .orElse(null);
        }

        /**
         * Chama um modelo, repetindo-o só para erro transitório. Cota/sobrecarga/404 saem na hora (quem resolve é o
         * próximo degrau). Cada requisição é reservada no controle antes e registrada depois.
         */
        private Resultado noModelo(String modelo, Papel papel) {
            int repeticoes = 0;
            while (true) {
                if (!podeGastar()) throw (ultima != null ? ultima : semModelo()).comTentativas(chamadas.get());
                ControleCotaIa.Reserva reserva = controle.reservar(modelo, estimativa);
                if (reserva == null) {
                    // outra leitura pegou a última vaga entre a escolha e a reserva: é como um 429, sem gastar nada
                    falhas.merge(modelo, 1, Integer::sum);
                    throw FalhaIa.cotaPorMinuto("Sem cota agora no " + modelo + " (limite do plano).", null);
                }
                int n = chamadas.incrementAndGet();
                Papel p = repeticoes == 0 ? papel : Papel.REPETICAO;
                Instant momento = Instant.now();
                long inicio = System.nanoTime();
                GeminiClient.Geracao g = null;
                try {
                    g = cliente.gerar(modelo, documentos, prompt, configMinima);
                    controle.concluir(reserva, g.tokensEntrada(), g.tokensSaida());
                    DadosExtraidos dados = parser.parse(g.texto());
                    registrar(momento, modelo, p, Ocorrencia.OK, 200, g, inicio);
                    return new Resultado(dados, modelo, n, controle.degrau(modelo), id);
                } catch (FalhaIa e) {
                    Ocorrencia o = classificar(e);
                    controle.registrarFalha(modelo, o, e.tentarDepois(), e.limiteInformado());
                    registrar(momento, modelo, p, o, e.status(), g, inicio);
                    ultima = e;
                    if (e.status() == 400 && !configMinima && CONFIG_RECUSADA.matcher(e.getMessage()).find()) {
                        log.warn("[Gemini] generationConfig otimizado recusado — repetindo com configuração mínima.");
                        configMinima = true;
                        repeticoes++;
                        continue;
                    }
                    if (o != Ocorrencia.ERRO) {
                        log.warn("[Gemini] {} → {} ({}). Descendo de degrau.", modelo, o, e.getMessage());
                        falhas.merge(modelo, 1, Integer::sum);
                        throw e;
                    }
                    if (!e.retentavel()) throw e;
                    if (repeticoes + 1 >= cfg.maxTentativas() || prazoEstourado()) {
                        falhas.merge(modelo, 1, Integer::sum);
                        throw e;
                    }
                    repeticoes++;
                    esperar(repeticoes);
                }
            }
        }

        /** Devolve a primeira das duas que terminar bem; se as duas falharem, null (descer) ou o erro definitivo. */
        private Resultado primeiraBoa(CompletableFuture<Resultado> a, CompletableFuture<Resultado> b) {
            while (true) {
                if (terminouBem(a)) return a.join();
                if (terminouBem(b)) return b.join();
                boolean aFalhou = a.isCompletedExceptionally();
                boolean bFalhou = b.isCompletedExceptionally();
                if (aFalhou && bFalhou) {
                    FalhaIa fa = falha(a), fb = falha(b);
                    if (definitiva(fa) && definitiva(fb)) throw fb.comTentativas(chamadas.get());
                    return null;
                }
                CompletableFuture<?> aguardar = aFalhou ? b : bFalhou ? a : CompletableFuture.anyOf(a, b);
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

        private void registrar(Instant momento, String modelo, Papel papel, Ocorrencia o, int status,
                GeminiClient.Geracao g, long inicioNanos) {
            long ms = (System.nanoTime() - inicioNanos) / 1_000_000;
            uso.registrar(new UsoIa(momento, id, modelo, controle.degrau(modelo), papel, o, status,
                    g == null ? null : g.tokensEntrada(), g == null ? null : g.tokensSaida(),
                    g == null ? null : g.tokensRaciocinio(), estimativa, ms, contexto.modo(), documentos.size(),
                    contexto.origem()));
        }

        /** A chamada em outra thread loga com o mesmo id de requisição de quem pediu a leitura. */
        private <T> Supplier<T> comMdc(Supplier<T> tarefa) {
            return () -> {
                if (mdc != null) MDC.setContextMap(mdc);
                try {
                    return tarefa.get();
                } finally {
                    MDC.clear();
                }
            };
        }

        private boolean podeGastar() {
            return chamadas.get() < Math.max(1, cfg.maxChamadasPorLeitura());
        }

        private boolean prazoEstourado() {
            return System.nanoTime() > limite;
        }
    }

    /** Nenhum modelo da cadeia tem vez: todos sem cota, pausados ou fora. */
    private FalhaIa semModelo() {
        return new FalhaIa("Nenhum modelo de IA com cota disponível agora (" + String.join(", ", controle.cadeia())
                + "). Tente de novo em alguns minutos ou preencha à mão; a situação de cada um está em /swagger/uso-ia.",
                429, false);
    }

    static Ocorrencia classificar(FalhaIa e) {
        if (e.modeloIndisponivel()) return Ocorrencia.INDISPONIVEL;
        if (quotaDiaria(e)) return Ocorrencia.COTA_DIA;
        if (e.cotaPorMinuto() || e.status() == 429) return Ocorrencia.COTA_MINUTO;
        if (e.sobrecarga()) return Ocorrencia.SOBRECARGA;
        return Ocorrencia.ERRO;
    }

    /** Erro que outro modelo não resolve (chave inválida, bloqueio por política, pedido malformado). */
    private static boolean definitiva(FalhaIa e) {
        return classificar(e) == Ocorrencia.ERRO && !e.retentavel();
    }

    private static boolean quotaDiaria(FalhaIa e) {
        return e.cotaDiaria() || (e.status() == 429 && !e.cotaPorMinuto() && QUOTA_DIARIA.matcher(e.getMessage()).find());
    }

    /** Prompt ≈ 1 token a cada 3,5 caracteres; cada imagem ou página de PDF ≈ {@link #TOKENS_POR_ARQUIVO}. */
    static int estimarTokens(List<Documento> documentos, String prompt) {
        long total = (long) Math.ceil(prompt.length() / 3.5);
        for (Documento d : documentos) total += (long) TOKENS_POR_ARQUIVO * paginas(d);
        return (int) Math.min(total, Integer.MAX_VALUE);
    }

    /** Páginas de um PDF contadas pelos objetos "/Type /Page" (PDF com páginas comprimidas conta 1). */
    static int paginas(Documento d) {
        if (!d.ehPdf()) return 1;
        Matcher m = PAGINA_PDF.matcher(new String(d.conteudo(), StandardCharsets.ISO_8859_1));
        int n = 0;
        while (m.find() && n < 200) n++;
        return Math.max(1, n);
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

    private static FalhaIa comoFalhaIa(Throwable t) {
        return t instanceof FalhaIa f ? f : new FalhaIa("Erro inesperado na extração: " + t, 0, false, t);
    }

    @Override
    public void destroy() {
        executor.shutdownNow();
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
