package br.com.rdamasio.helpagent.usoia;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Registro das chamadas ao Gemini (tabela {@code uso_ia}) e o painel "Uso da IA".
 *
 * <p>Gravar nunca derruba uma leitura: se o banco falhar, fica só o log. Na subida, remonta os contadores do dia no
 * {@link ControleCotaIa} (senão um reinício "devolveria" a cota já gasta) e apaga o que passou de {@link #RETENCAO}.
 *
 * <p>Também publica métricas (Micrometer → {@code /actuator/prometheus}) para o monitoramento de uma aplicação maior
 * alertar sem ler esta tabela: {@code helpagent_ia_requisicoes_total{modelo,ocorrencia,papel}},
 * {@code helpagent_ia_tokens_total{modelo,tipo}}, {@code helpagent_ia_duracao_seconds{modelo}} e, por modelo da
 * cadeia, {@code helpagent_ia_cota_dia_usada} / {@code helpagent_ia_cota_dia_limite}.
 */
@Service
public class UsoIaService {

    private static final Logger log = LoggerFactory.getLogger(UsoIaService.class);
    static final Duration RETENCAO = Duration.ofDays(90);

    /** Uma linha do painel: uma requisição enviada ao Google. */
    public record Chamada(Instant momento, String modelo, int degrau, Papel papel, Ocorrencia ocorrencia, int status,
            Integer tokensEntrada, Integer tokensSaida, long duracaoMs, String modo, int arquivos, String origem) {

        static Chamada de(UsoIa u) {
            return new Chamada(u.getMomento(), u.getModelo(), u.getDegrau(), u.getPapel(), u.getOcorrencia(),
                    u.getStatusHttp(), u.getTokensEntrada(), u.getTokensSaida(), u.getDuracaoMs(), u.getModo(),
                    u.getArquivos(), u.getOrigem());
        }
    }

    /**
     * Totais do dia do Google (desde a meia-noite do Pacífico), só do que passou por este servidor.
     *
     * @param foraDoPrincipal leituras que terminaram num modelo abaixo do primeiro da cadeia
     * @param picoPorMinuto   maior número de requisições numa janela de 60 s — é o "RPM" que o painel do Google mostra
     */
    public record Totais(int leituras, int requisicoes, long tokensEntrada, long tokensSaida, int erros,
            int foraDoPrincipal, int picoPorMinuto, Instant momentoDoPico) {
    }

    public record Hora(Instant inicio, int requisicoes, int erros, long tokensEntrada) {
    }

    /** @param viradaDoDia quando a cota diária do Google zera (próxima meia-noite do Pacífico) */
    public record Painel(Instant agora, Instant viradaDoDia, List<ControleCotaIa.Situacao> modelos, Totais hoje,
            List<Hora> ultimas24h, List<Chamada> ultimas) {
    }

    private final UsoIaRepository repo;
    private final ControleCotaIa controle;
    private final Clock relogio;
    private final MeterRegistry metricas;

    @Autowired
    public UsoIaService(UsoIaRepository repo, ControleCotaIa controle, MeterRegistry metricas) {
        this(repo, controle, Clock.systemUTC(), metricas);
    }

    UsoIaService(UsoIaRepository repo, ControleCotaIa controle, Clock relogio, MeterRegistry metricas) {
        this.repo = repo;
        this.controle = controle;
        this.relogio = relogio;
        this.metricas = metricas;
        for (String modelo : controle.cadeia()) {
            Gauge.builder("helpagent.ia.cota.dia.usada", () -> doModelo(modelo, ControleCotaIa.Situacao::requisicoesDia))
                    .tag("modelo", modelo).description("Requisições no dia do Google (meia-noite do Pacífico)")
                    .register(metricas);
            Gauge.builder("helpagent.ia.cota.dia.limite", () -> doModelo(modelo, ControleCotaIa.Situacao::rpd))
                    .tag("modelo", modelo).description("Limite diário em uso (configurado ou informado pelo Google)")
                    .register(metricas);
        }
    }

    private double doModelo(String modelo, java.util.function.ToIntFunction<ControleCotaIa.Situacao> campo) {
        return controle.situacao().stream().filter(s -> s.modelo().equals(modelo)).mapToInt(campo).findFirst().orElse(0);
    }

    public void registrar(UsoIa chamada) {
        try {
            metricas.counter("helpagent.ia.requisicoes", "modelo", chamada.getModelo(),
                    "ocorrencia", chamada.getOcorrencia().name(), "papel", chamada.getPapel().name()).increment();
            if (chamada.getTokensEntrada() != null) {
                metricas.counter("helpagent.ia.tokens", "modelo", chamada.getModelo(), "tipo", "entrada")
                        .increment(chamada.getTokensEntrada());
            }
            if (chamada.getTokensSaida() != null) {
                metricas.counter("helpagent.ia.tokens", "modelo", chamada.getModelo(), "tipo", "saida")
                        .increment(chamada.getTokensSaida());
            }
            metricas.timer("helpagent.ia.duracao", "modelo", chamada.getModelo())
                    .record(Duration.ofMillis(chamada.getDuracaoMs()));
        } catch (RuntimeException e) {
            log.debug("[Uso IA] métrica não registrada: {}", e.getMessage());
        }
        try {
            repo.save(chamada);
        } catch (RuntimeException e) {
            log.warn("[Uso IA] não consegui registrar a chamada ao {}: {}", chamada.getModelo(), e.getMessage());
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void aoSubir() {
        try {
            int apagadas = repo.apagarAntesDe(relogio.instant().minus(RETENCAO));
            if (apagadas > 0) log.info("[Uso IA] {} registro(s) com mais de {} dias apagados.", apagadas, RETENCAO.toDays());
            List<UsoIa> hoje = repo.findByMomentoGreaterThanEqualOrderByMomento(inicioDoDia());
            controle.recarregar(hoje);
            log.info("[Uso IA] contadores do dia remontados: {} requisição(ões) desde a meia-noite do Pacífico.", hoje.size());
        } catch (RuntimeException e) {
            log.warn("[Uso IA] não consegui remontar os contadores do dia: {}", e.getMessage());
        }
    }

    public Painel painel() {
        Instant agora = relogio.instant();
        Instant inicioDia = inicioDoDia();
        Instant desde24h = agora.minus(Duration.ofHours(24)).truncatedTo(ChronoUnit.HOURS);
        List<UsoIa> recentes = repo.findByMomentoGreaterThanEqualOrderByMomento(
                inicioDia.isBefore(desde24h) ? inicioDia : desde24h);

        List<UsoIa> doDia = recentes.stream().filter(u -> !u.getMomento().isBefore(inicioDia)).toList();
        List<Hora> horas = new ArrayList<>();
        for (Instant h = desde24h; !h.isAfter(agora); h = h.plus(Duration.ofHours(1))) {
            Instant ini = h, fim = h.plus(Duration.ofHours(1));
            List<UsoIa> naHora = recentes.stream()
                    .filter(u -> !u.getMomento().isBefore(ini) && u.getMomento().isBefore(fim)).toList();
            horas.add(new Hora(ini, naHora.size(),
                    (int) naHora.stream().filter(u -> u.getOcorrencia() != Ocorrencia.OK).count(),
                    naHora.stream().mapToLong(UsoIa::getEstimativaOuReal).sum()));
        }
        List<Chamada> ultimas = repo.findTop40ByOrderByMomentoDesc().stream().map(Chamada::de).toList();
        return new Painel(agora, LocalDate.ofInstant(agora, ControleCotaIa.FUSO_GOOGLE).plusDays(1)
                .atStartOfDay(ControleCotaIa.FUSO_GOOGLE).toInstant(), controle.situacao(), totais(doDia), horas, ultimas);
    }

    static Totais totais(List<UsoIa> doDia) {
        Set<String> leituras = new HashSet<>();
        Set<String> foraDoPrincipal = new HashSet<>();
        long entrada = 0, saida = 0;
        int erros = 0;
        for (UsoIa u : doDia) {
            leituras.add(u.getLeitura());
            if (u.getOcorrencia() == Ocorrencia.OK && u.getDegrau() > 0) foraDoPrincipal.add(u.getLeitura());
            entrada += u.getEstimativaOuReal();
            if (u.getTokensSaida() != null) saida += u.getTokensSaida();
            if (u.getOcorrencia() != Ocorrencia.OK) erros++;
        }
        // pico: janela móvel de 60 s sobre as chamadas em ordem de momento
        int pico = 0;
        Instant momentoPico = null;
        for (int ini = 0, fim = 0; fim < doDia.size(); fim++) {
            Instant limite = doDia.get(fim).getMomento().minusSeconds(60);
            while (!doDia.get(ini).getMomento().isAfter(limite)) ini++;
            if (fim - ini + 1 > pico) {
                pico = fim - ini + 1;
                momentoPico = doDia.get(ini).getMomento();
            }
        }
        return new Totais(leituras.size(), doDia.size(), entrada, saida, erros, foraDoPrincipal.size(), pico, momentoPico);
    }

    private Instant inicioDoDia() {
        return LocalDate.ofInstant(relogio.instant(), ControleCotaIa.FUSO_GOOGLE)
                .atStartOfDay(ControleCotaIa.FUSO_GOOGLE).toInstant();
    }
}
