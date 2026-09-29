package br.com.rdamasio.helpagent.usoia;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import br.com.rdamasio.helpagent.config.HelpAgentProperties.ModeloIa;
import br.com.rdamasio.helpagent.usoia.ControleCotaIa.Estado;

/** Contas do controle de cota: janela do minuto, dia do Google (Pacífico), pausas por tipo de 429 e recarga. */
class ControleCotaIaTest {

    // 15:00 UTC = 08:00 no Pacífico (horário de verão): meio do dia do Google
    private final AtomicReference<Instant> agora = new AtomicReference<>(Instant.parse("2026-09-25T15:00:00Z"));
    private final Clock relogio = new Clock() {
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return agora.get(); }
    };

    private ControleCotaIa controle(ModeloIa... cadeia) {
        return new ControleCotaIa(List.of(cadeia), relogio);
    }

    @Test
    void janelaDoMinutoLiberaDepoisDe60Segundos() {
        var c = controle(new ModeloIa("m", 2, 1_000_000, 100));

        assertThat(c.reservar("m", 10)).isNotNull();
        assertThat(c.reservar("m", 10)).isNotNull();
        assertThat(c.reservar("m", 10)).isNull(); // 3ª no mesmo minuto
        assertThat(c.estado("m", 0)).isEqualTo(Estado.LIMITE_LOCAL_MINUTO);

        agora.set(agora.get().plusSeconds(61));
        assertThat(c.reservar("m", 10)).isNotNull();
    }

    @Test
    void tokensPorMinutoUsamOReal() {
        var c = controle(new ModeloIa("m", 100, 5_000, 100));
        var r = c.reservar("m", 1_000);
        c.concluir(r, 4_500, 200); // o Google contou bem mais que a estimativa

        assertThat(c.temVez("m", 400)).isTrue();
        assertThat(c.temVez("m", 600)).isFalse(); // 4.500 + 600 > 5.000
    }

    @Test
    void diaViraAMeiaNoiteDoPacificoENaoDeBrasilia() {
        var c = controle(new ModeloIa("m", 100, 1_000_000, 1));
        c.reservar("m", 10);
        assertThat(c.estado("m", 0)).isEqualTo(Estado.LIMITE_LOCAL_DIA);

        // 03:00 em Brasília = 23:00 no Pacífico: ainda o mesmo dia do Google
        agora.set(Instant.parse("2026-09-26T06:00:00Z"));
        assertThat(c.estado("m", 0)).isEqualTo(Estado.LIMITE_LOCAL_DIA);
        // 04:01 em Brasília = 00:01 no Pacífico: virou
        agora.set(Instant.parse("2026-09-26T07:01:00Z"));
        assertThat(c.estado("m", 0)).isEqualTo(Estado.DISPONIVEL);
    }

    @Test
    void cotaDiariaDoGooglePausaEAprendeOLimiteMenor() {
        var c = controle(new ModeloIa("m", 100, 1_000_000, 250));
        c.registrarFalha("m", Ocorrencia.COTA_DIA, null, 20);

        assertThat(c.estado("m", 0)).isEqualTo(Estado.SEM_COTA_DIA);
        assertThat(c.situacao().getFirst().rpd()).isEqualTo(20);

        agora.set(agora.get().plus(ControleCotaIa.PAUSA_COTA_DIA).plusSeconds(1));
        assertThat(c.temVez("m", 0)).isTrue(); // sondado de novo
    }

    @Test
    void cotaPorMinutoRespeitaORetryDelayDentroDeLimites() {
        var c = controle(new ModeloIa("m", 100, 1_000_000, 100));
        c.registrarFalha("m", Ocorrencia.COTA_MINUTO, Duration.ofMillis(7_580), null);
        assertThat(c.temVez("m", 0)).isFalse();
        agora.set(agora.get().plusSeconds(8));
        assertThat(c.temVez("m", 0)).isTrue();

        c.registrarFalha("m", Ocorrencia.COTA_MINUTO, Duration.ofHours(3), null); // absurdo → no máximo 2 min
        agora.set(agora.get().plusSeconds(121));
        assertThat(c.temVez("m", 0)).isTrue();
    }

    @Test
    void sobrecargaSoEsfriaNaoTiraAVez() {
        var c = controle(new ModeloIa("m", 100, 1_000_000, 100));
        c.registrarFalha("m", Ocorrencia.SOBRECARGA, null, null);

        assertThat(c.estado("m", 0)).isEqualTo(Estado.ESFRIANDO);
        assertThat(c.temVez("m", 0)).isTrue();
    }

    @Test
    void recargaNaSubidaDevolveOQueJaFoiGastoHoje() {
        var c = controle(new ModeloIa("m", 100, 1_000_000, 3));
        Instant ontem = Instant.parse("2026-09-24T15:00:00Z");
        c.recarregar(List.of(chamada("m", ontem), chamada("m", agora.get().minusSeconds(3600)),
                chamada("m", agora.get().minusSeconds(10)), chamada("m", agora.get().minusSeconds(5))));

        var s = c.situacao().getFirst();
        assertThat(s.requisicoesDia()).isEqualTo(3); // a de ontem não conta
        assertThat(s.requisicoesMinuto()).isEqualTo(2);
        assertThat(s.estado()).isEqualTo(Estado.LIMITE_LOCAL_DIA);
    }

    @Test
    void modeloForaDaCadeiaNaoTemLimite() {
        var c = controle(new ModeloIa("m", 1, 1, 1));
        assertThat(c.reservar("forcado", 1_000_000)).isNotNull();
        assertThat(c.degrau("forcado")).isEqualTo(-1);
    }

    private static UsoIa chamada(String modelo, Instant quando) {
        return new UsoIa(quando, "l", modelo, 0, Papel.PRINCIPAL, Ocorrencia.OK, 200, 2000, 100, null, 2000, 3000,
                "CAPEX", 1, null);
    }
}
