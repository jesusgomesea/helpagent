package br.com.rdamasio.helpagent.usoia;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import br.com.rdamasio.helpagent.config.HelpAgentProperties;
import br.com.rdamasio.helpagent.config.HelpAgentProperties.ModeloIa;

/**
 * Controle de cota do Gemini, por modelo da cadeia ({@code helpagent.gemini.cadeia}). Fica em memória e responde
 * duas perguntas antes de cada chamada: "este modelo tem vez agora?" e "reserve uma requisição nele".
 *
 * <p>Por que existe (29/09/2026): o RPM do projeto subiu de repente, e o servidor só descobria o limite levando
 * 429 — que também conta como requisição. Agora ele conta sozinho, e desce de degrau <b>sem chamar</b> quando o
 * modelo passaria do limite do plano:
 * <ul>
 *   <li><b>minuto</b>: requisições e tokens de entrada numa janela móvel de 60 s (o que o Google chama de RPM/TPM);</li>
 *   <li><b>dia</b>: requisições desde a meia-noite do Pacífico, quando a cota diária do Google vira (RPD).</li>
 * </ul>
 * O Google conta por PROJETO: chamadas de fora deste servidor (outra máquina, o HTML v3.5 com a chave antiga)
 * gastam a mesma cota e ele não as vê. Por isso as respostas do Google continuam mandando:
 * <ul>
 *   <li>429 do dia → modelo pausado por {@link #PAUSA_COTA_DIA} e sondado de novo (o gratuito libera aos poucos:
 *       medido em 25/09, voltou ~25 min depois). Se o Google informar o limite e ele for menor que o configurado,
 *       passa a valer o dele (log "limite aprendido");</li>
 *   <li>429 do minuto → pausado pelo "tente de novo em" que o Google mandou (5 s a 2 min; 60 s se não veio);</li>
 *   <li>503/tempo esgotado → só "esfria" por {@link #RESFRIAMENTO}: outro modelo é preferido, mas se todos
 *       estiverem esfriando ele ainda pode ser usado (sobrecarga é passageira);</li>
 *   <li>404 → fora da cadeia até o servidor reiniciar.</li>
 * </ul>
 * Os contadores do dia voltam da tabela {@code uso_ia} quando o servidor sobe ({@link UsoIaService}).
 */
@Component
public class ControleCotaIa {

    private static final Logger log = LoggerFactory.getLogger(ControleCotaIa.class);

    /** A cota diária do Google vira à meia-noite do Pacífico (4h ou 5h em Brasília). */
    public static final ZoneId FUSO_GOOGLE = ZoneId.of("America/Los_Angeles");
    public static final Duration PAUSA_COTA_DIA = Duration.ofMinutes(15);
    public static final Duration PAUSA_COTA_MINUTO = Duration.ofSeconds(60);
    public static final Duration RESFRIAMENTO = Duration.ofSeconds(60);
    private static final Duration JANELA = Duration.ofSeconds(60);
    /** A partir desta fração do limite diário, avisa no log (uma vez por dia e modelo) e o painel pinta de alerta. */
    public static final double ALERTA = 0.8;

    /** Situação de um modelo agora. Os "LIMITE_LOCAL_*" são do nosso contador; os "SEM_COTA_*", do Google. */
    public enum Estado {
        DISPONIVEL, ESFRIANDO, LIMITE_LOCAL_MINUTO, LIMITE_LOCAL_DIA, SEM_COTA_MINUTO, SEM_COTA_DIA, INDISPONIVEL;

        boolean temVez() {
            return this == DISPONIVEL || this == ESFRIANDO;
        }
    }

    /** Vaga reservada numa chamada; {@link #concluir} troca a estimativa pelos tokens reais. */
    public static final class Reserva {
        private final String modelo;
        private final Instant momento;
        private int tokens;

        private Reserva(String modelo, Instant momento, int tokens) {
            this.modelo = modelo;
            this.momento = momento;
            this.tokens = tokens;
        }
    }

    /** Foto de um modelo para o painel. {@code ate} = fim da pausa/resfriamento (null se não houver). */
    public record Situacao(String modelo, int degrau, int rpm, int tpm, int rpd, Integer rpdAprendido,
            int requisicoesMinuto, long tokensMinuto, int requisicoesDia, long tokensEntradaDia, long tokensSaidaDia,
            int errosDia, int pulosDia, Estado estado, Instant ate, String motivo) {
    }

    private final class Degrau {
        final int indice;
        final ModeloIa limites;
        final Deque<Reserva> minuto = new ArrayDeque<>();
        LocalDate dia;
        int requisicoesDia;
        long tokensEntradaDia;
        long tokensSaidaDia;
        int errosDia;
        int pulosDia;
        boolean alertado;
        Integer rpdAprendido;
        Instant pausaAte;
        Estado motivoPausa;
        Instant esfriaAte;
        String indisponivel;

        Degrau(int indice, ModeloIa limites) {
            this.indice = indice;
            this.limites = limites;
            this.dia = hoje();
        }

        int rpd() {
            return rpdAprendido != null ? Math.min(rpdAprendido, limites.rpd()) : limites.rpd();
        }

        /** Tira da janela o que passou de 60 s e zera o dia quando ele vira no Pacífico. */
        void atualizar(Instant agora) {
            while (!minuto.isEmpty() && !minuto.peekFirst().momento.isAfter(agora.minus(JANELA))) minuto.pollFirst();
            LocalDate d = hoje();
            if (!d.equals(dia)) {
                dia = d;
                requisicoesDia = 0;
                tokensEntradaDia = 0;
                tokensSaidaDia = 0;
                errosDia = 0;
                pulosDia = 0;
                alertado = false;
                if (motivoPausa == Estado.SEM_COTA_DIA) pausaAte = null;
            }
        }

        long tokensMinuto() {
            return minuto.stream().mapToLong(r -> r.tokens).sum();
        }

        Estado estado(Instant agora, int estimativa) {
            atualizar(agora);
            if (indisponivel != null) return Estado.INDISPONIVEL;
            if (pausaAte != null && agora.isBefore(pausaAte)) return motivoPausa;
            if (requisicoesDia >= rpd()) return Estado.LIMITE_LOCAL_DIA;
            if (minuto.size() >= limites.rpm() || tokensMinuto() + estimativa > limites.tpm()) {
                return Estado.LIMITE_LOCAL_MINUTO;
            }
            if (esfriaAte != null && agora.isBefore(esfriaAte)) return Estado.ESFRIANDO;
            return Estado.DISPONIVEL;
        }
    }

    private final Map<String, Degrau> degraus = new LinkedHashMap<>();
    private final Clock relogio;

    @Autowired
    public ControleCotaIa(HelpAgentProperties props) {
        this(props.gemini().cadeia(), Clock.systemUTC());
    }

    public ControleCotaIa(List<ModeloIa> cadeia, Clock relogio) {
        this.relogio = relogio;
        for (ModeloIa m : cadeia) {
            if (m.modelo() == null || m.modelo().isBlank()) continue;
            degraus.putIfAbsent(m.modelo().strip(), new Degrau(degraus.size(), m));
        }
    }

    private LocalDate hoje() {
        return LocalDate.ofInstant(relogio.instant(), FUSO_GOOGLE);
    }

    /** Modelos da cadeia, do melhor para o pior. */
    public synchronized List<String> cadeia() {
        return List.copyOf(degraus.keySet());
    }

    /** 0 = melhor modelo; -1 = fora da cadeia (modelo forçado). */
    public synchronized int degrau(String modelo) {
        Degrau d = degraus.get(modelo);
        return d == null ? -1 : d.indice;
    }

    public synchronized Estado estado(String modelo, int estimativa) {
        Degrau d = degraus.get(modelo);
        return d == null ? Estado.DISPONIVEL : d.estado(relogio.instant(), estimativa);
    }

    /** Tem vez agora para uma chamada de {@code estimativa} tokens de entrada? (esfriando conta como sim) */
    public synchronized boolean temVez(String modelo, int estimativa) {
        return estado(modelo, estimativa).temVez();
    }

    public synchronized boolean esfriando(String modelo) {
        return estado(modelo, 0) == Estado.ESFRIANDO;
    }

    /**
     * Reserva uma requisição: conta no minuto e no dia ANTES de chamar, para duas leituras simultâneas não
     * passarem juntas pelo mesmo último lugar. Devolve null se o modelo não tem vez (a leitura desce de degrau).
     * Modelo fora da cadeia (forçado) não tem limite, mas também é contado.
     */
    public synchronized Reserva reservar(String modelo, int estimativa) {
        Instant agora = relogio.instant();
        Degrau d = degraus.get(modelo);
        if (d == null) return new Reserva(modelo, agora, estimativa);
        Estado e = d.estado(agora, estimativa);
        if (!e.temVez()) {
            d.pulosDia++;
            return null;
        }
        Reserva r = new Reserva(modelo, agora, estimativa);
        d.minuto.addLast(r);
        d.requisicoesDia++;
        d.tokensEntradaDia += estimativa;
        if (!d.alertado && d.requisicoesDia >= Math.ceil(d.rpd() * ALERTA)) {
            d.alertado = true;
            log.warn("[Cota IA] {} já usou {} de {} requisições hoje (dia do Google, vira à meia-noite do Pacífico).",
                    modelo, d.requisicoesDia, d.rpd());
        }
        return r;
    }

    /** Depois da chamada: troca a estimativa pelos tokens que o Google contou (se ele contou). */
    public synchronized void concluir(Reserva r, Integer tokensEntrada, Integer tokensSaida) {
        Degrau d = r == null ? null : degraus.get(r.modelo);
        if (d == null) return;
        if (tokensEntrada != null) {
            d.tokensEntradaDia += tokensEntrada - r.tokens;
            r.tokens = tokensEntrada;
        }
        if (tokensSaida != null) d.tokensSaidaDia += tokensSaida;
    }

    /**
     * O Google recusou: aplica a pausa do tipo de falha (ver javadoc da classe).
     *
     * @param tentarDepois    retryDelay do 429 (null se não veio)
     * @param limiteInformado quotaValue do 429 (null se não veio)
     */
    public synchronized void registrarFalha(String modelo, Ocorrencia o, Duration tentarDepois, Integer limiteInformado) {
        Degrau d = degraus.get(modelo);
        if (d == null || o == Ocorrencia.OK) return;
        Instant agora = relogio.instant();
        d.atualizar(agora);
        d.errosDia++;
        switch (o) {
            case COTA_DIA -> {
                pausar(d, Estado.SEM_COTA_DIA, agora.plus(PAUSA_COTA_DIA));
                if (limiteInformado != null && limiteInformado > 0 && limiteInformado < d.rpd()) {
                    d.rpdAprendido = limiteInformado;
                    log.warn("[Cota IA] {}: o Google informou limite de {} requisições/dia (configurado: {}). "
                            + "Passando a usar {}; ajuste helpagent.gemini.cadeia.", modelo, limiteInformado,
                            d.limites.rpd(), limiteInformado);
                }
                log.warn("[Cota IA] {} sem cota diária no Google; fora da cadeia até {} (depois, sondado de novo).",
                        modelo, d.pausaAte);
            }
            case COTA_MINUTO -> {
                Duration pausa = tentarDepois == null ? PAUSA_COTA_MINUTO
                        : limitar(tentarDepois, Duration.ofSeconds(5), Duration.ofMinutes(2));
                pausar(d, Estado.SEM_COTA_MINUTO, agora.plus(pausa));
                log.warn("[Cota IA] {} sem cota por minuto no Google; descansa {} s.", modelo, pausa.toSeconds());
            }
            case SOBRECARGA -> d.esfriaAte = agora.plus(RESFRIAMENTO);
            case INDISPONIVEL -> marcar(d, "o Google respondeu 404 para este modelo");
            default -> {
                // erro comum: só conta
            }
        }
    }

    /** Tira o modelo da cadeia até o servidor reiniciar (ex.: a chave não enxerga o modelo). */
    public synchronized void marcarIndisponivel(String modelo, String motivo) {
        Degrau d = degraus.get(modelo);
        if (d != null) marcar(d, motivo);
    }

    private void marcar(Degrau d, String motivo) {
        if (d.indisponivel == null) log.warn("[Cota IA] {} fora da cadeia: {}.", nome(d), motivo);
        d.indisponivel = motivo;
    }

    private String nome(Degrau d) {
        return d.limites.modelo();
    }

    private static void pausar(Degrau d, Estado motivo, Instant ate) {
        if (d.pausaAte == null || ate.isAfter(d.pausaAte) || d.motivoPausa != motivo) {
            d.pausaAte = ate;
            d.motivoPausa = motivo;
        }
    }

    private static Duration limitar(Duration d, Duration min, Duration max) {
        return d.compareTo(min) < 0 ? min : d.compareTo(max) > 0 ? max : d;
    }

    /**
     * Remonta os contadores a partir do que já foi registrado hoje (o servidor reiniciou). Recebe as chamadas desde
     * a meia-noite do Pacífico; as do último minuto voltam também para a janela.
     */
    public synchronized void recarregar(List<UsoIa> chamadas) {
        Instant agora = relogio.instant();
        LocalDate dia = hoje();
        for (UsoIa u : chamadas) {
            Degrau d = degraus.get(u.getModelo());
            if (d == null || !LocalDate.ofInstant(u.getMomento(), FUSO_GOOGLE).equals(dia)) continue;
            d.atualizar(agora);
            d.requisicoesDia++;
            int entrada = u.getTokensEntrada() != null ? u.getTokensEntrada() : u.getEstimativa();
            d.tokensEntradaDia += entrada;
            if (u.getTokensSaida() != null) d.tokensSaidaDia += u.getTokensSaida();
            if (u.getOcorrencia() != Ocorrencia.OK) d.errosDia++;
            if (u.getMomento().isAfter(agora.minus(JANELA))) d.minuto.addLast(new Reserva(u.getModelo(), u.getMomento(), entrada));
            if (u.getOcorrencia() == Ocorrencia.COTA_DIA && u.getMomento().plus(PAUSA_COTA_DIA).isAfter(agora)) {
                pausar(d, Estado.SEM_COTA_DIA, u.getMomento().plus(PAUSA_COTA_DIA));
            }
        }
        for (Degrau d : degraus.values()) d.alertado = d.requisicoesDia >= Math.ceil(d.rpd() * ALERTA);
    }

    /** Foto de todos os modelos da cadeia, na ordem. */
    public synchronized List<Situacao> situacao() {
        Instant agora = relogio.instant();
        List<Situacao> lista = new ArrayList<>();
        for (Degrau d : degraus.values()) {
            Estado e = d.estado(agora, 0);
            Instant ate = switch (e) {
                case SEM_COTA_DIA, SEM_COTA_MINUTO -> d.pausaAte;
                case ESFRIANDO -> d.esfriaAte;
                case LIMITE_LOCAL_MINUTO -> d.minuto.isEmpty() ? null : d.minuto.peekFirst().momento.plus(JANELA);
                case LIMITE_LOCAL_DIA -> d.dia.plusDays(1).atStartOfDay(FUSO_GOOGLE).toInstant();
                default -> null;
            };
            lista.add(new Situacao(nome(d), d.indice, d.limites.rpm(), d.limites.tpm(), d.rpd(), d.rpdAprendido,
                    d.minuto.size(), d.tokensMinuto(), d.requisicoesDia, d.tokensEntradaDia, d.tokensSaidaDia,
                    d.errosDia, d.pulosDia, e, ate, d.indisponivel));
        }
        return lista;
    }
}
