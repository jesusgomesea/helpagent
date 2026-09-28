package br.com.rdamasio.helpagent.extracao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import br.com.rdamasio.helpagent.config.HelpAgentProperties;
import tools.jackson.databind.json.JsonMapper;

/**
 * Política de tentativas/fallback da leitura por IA. É ela que decide quanto o usuário espera quando o
 * Google está lento ou sem quota — por isso cada regra do javadoc do {@link ExtratorIa} tem um teste aqui.
 */
class ExtratorIaTest {

    private static final String PRINCIPAL = "modelo-principal";
    private static final String RESERVA = "modelo-reserva";
    private static final String JSON_OK = """
            {"titulo":"Teste","itens":[{"produto":"X","qtd":"1","valor_unit":"10,00","valor_total":"10,00"}],"total":"R$ 10,00"}
            """;

    private GeminiClient gemini;
    private ExtratorIa extrator;

    @BeforeEach
    void preparar() {
        gemini = mock(GeminiClient.class);
        extrator = novo(Clock.fixed(Instant.parse("2026-09-25T15:00:00Z"), ZoneOffset.UTC));
    }

    private ExtratorIa novo(Clock relogio) {
        return novo(relogio, Duration.ZERO);
    }

    /** @param reservaApos 0 = sem reserva em paralelo (os testes de política não dependem de tempo) */
    private ExtratorIa novo(Clock relogio, Duration reservaApos) {
        var cfg = new HelpAgentProperties.Gemini("chave", "http://x", PRINCIPAL, RESERVA, "", 3, Duration.ZERO, 1000,
                Duration.ofSeconds(30), "low", Duration.ZERO, reservaApos);
        var props = new HelpAgentProperties(cfg, null, new HelpAgentProperties.Armazenamento(Path.of(".")),
                new HelpAgentProperties.Seguranca(false), null, ZoneId.of("America/Sao_Paulo"), List.of());
        return new ExtratorIa(gemini, new RespostaIaParser(JsonMapper.builder().build()), props, relogio);
    }

    private void principalFalha(FalhaIa erro) {
        when(gemini.gerar(eq(PRINCIPAL), any(), anyString(), anyBoolean())).thenThrow(erro);
    }

    private void reservaResponde() {
        when(gemini.gerar(eq(RESERVA), any(), anyString(), anyBoolean())).thenReturn(JSON_OK);
    }

    @Test
    void caminhoFelizUsaSoOPrincipal() {
        when(gemini.gerar(eq(PRINCIPAL), any(), anyString(), anyBoolean())).thenReturn(JSON_OK);

        var r = extrator.extrair(List.of(), "prompt");

        assertThat(r.modelo()).isEqualTo(PRINCIPAL);
        assertThat(r.tentativas()).isEqualTo(1);
        verify(gemini, never()).gerar(eq(RESERVA), any(), anyString(), anyBoolean());
    }

    @Test
    void sobrecargaNoPrincipalVaiDiretoParaReservaSemRepetir() {
        principalFalha(FalhaIa.sobrecarga("API Gemini (503): high demand", 503, null));
        reservaResponde();

        var r = extrator.extrair(List.of(), "prompt");

        assertThat(r.modelo()).isEqualTo(RESERVA);
        assertThat(r.tentativas()).isEqualTo(2);
        verify(gemini, times(1)).gerar(eq(PRINCIPAL), any(), anyString(), anyBoolean());
    }

    @Test
    void casoReal28deSetembroAlternaDeVoltaParaOPrincipal() {
        // principal: 503; reserva: trava (30 s); principal, logo depois, responde em 2–3 s
        when(gemini.gerar(eq(PRINCIPAL), any(), anyString(), anyBoolean()))
                .thenThrow(FalhaIa.sobrecarga("API Gemini (503): high demand", 503, null))
                .thenReturn(JSON_OK);
        when(gemini.gerar(eq(RESERVA), any(), anyString(), anyBoolean()))
                .thenThrow(FalhaIa.sobrecarga("A IA não respondeu em 30 s", 0, null));

        var r = extrator.extrair(List.of(), "prompt");

        assertThat(r.modelo()).isEqualTo(PRINCIPAL);
        verify(gemini, times(1)).gerar(eq(RESERVA), any(), anyString(), anyBoolean()); // não insistiu 3x na reserva
        verify(gemini, times(2)).gerar(eq(PRINCIPAL), any(), anyString(), anyBoolean());
    }

    @Test
    void semPrincipalDisponivelNaoAlternaParaEle() {
        // principal sem cota: a alternância não pode mandar de volta para ele
        principalFalha(FalhaIa.cotaDiaria("API Gemini (429): You exceeded your current quota"));
        when(gemini.gerar(eq(RESERVA), any(), anyString(), anyBoolean()))
                .thenThrow(FalhaIa.sobrecarga("A IA não respondeu em 30 s", 0, null))
                .thenReturn(JSON_OK);

        assertThat(extrator.extrair(List.of(), "prompt").modelo()).isEqualTo(RESERVA);
        verify(gemini, times(1)).gerar(eq(PRINCIPAL), any(), anyString(), anyBoolean());
    }

    @Test
    void tempoEsgotadoContaComoSobrecarga() {
        principalFalha(FalhaIa.sobrecarga("A IA não respondeu em 30 s", 0, null));
        reservaResponde();

        assertThat(extrator.extrair(List.of(), "prompt").modelo()).isEqualTo(RESERVA);
        verify(gemini, times(1)).gerar(eq(PRINCIPAL), any(), anyString(), anyBoolean());
    }

    @Test
    void quotaDiariaTiraOPrincipalPorUmTempo() {
        principalFalha(new FalhaIa("API Gemini (429): Quota exceeded for metric generate_requests_per_day", 429, true));
        reservaResponde();

        extrator.extrair(List.of(), "prompt");
        extrator.extrair(List.of(), "prompt");

        // a segunda extração do dia nem tenta o principal
        verify(gemini, times(1)).gerar(eq(PRINCIPAL), any(), anyString(), anyBoolean());
        verify(gemini, times(2)).gerar(eq(RESERVA), any(), anyString(), anyBoolean());
    }

    @Test
    void passadaAPausaOPrincipalVoltaASerSondado() {
        principalFalha(new FalhaIa("API Gemini (429): quota per day", 429, true));
        reservaResponde();
        var agora = new java.util.concurrent.atomic.AtomicReference<>(Instant.parse("2026-09-25T15:00:00Z"));
        Clock relogio = new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return agora.get(); }
        };
        var ext = novo(relogio);

        ext.extrair(List.of(), "prompt");
        agora.set(agora.get().plus(ExtratorIa.PAUSA_COTA).minusSeconds(60));
        ext.extrair(List.of(), "prompt"); // ainda na pausa: nem tenta
        verify(gemini, times(1)).gerar(eq(PRINCIPAL), any(), anyString(), anyBoolean());

        agora.set(agora.get().plusSeconds(120));
        ext.extrair(List.of(), "prompt"); // pausa acabou: sonda de novo
        verify(gemini, times(2)).gerar(eq(PRINCIPAL), any(), anyString(), anyBoolean());
    }

    @Test
    void limitePorMinutoNaoETratadoComoCotaDiaria() {
        // mensagem real do 429 por minuto: tem "Quota" e "free_tier", mas não é o limite do dia
        when(gemini.gerar(eq(PRINCIPAL), any(), anyString(), anyBoolean()))
                .thenThrow(new FalhaIa("API Gemini (429): Quota exceeded for metric: "
                        + "generate_content_free_tier_requests, limit: 5 per minute", 429, true))
                .thenReturn(JSON_OK);

        var r = extrator.extrair(List.of(), "prompt");

        assertThat(r.modelo()).isEqualTo(PRINCIPAL); // repetiu o principal em vez de marcá-lo esgotado
        extrator.extrair(List.of(), "prompt");
        verify(gemini, times(3)).gerar(eq(PRINCIPAL), any(), anyString(), anyBoolean());
    }

    @Test
    void cotaDiariaVindaDoCampoEstruturadoMarcaEsgotado() {
        principalFalha(FalhaIa.cotaDiaria("API Gemini (429): You exceeded your current quota"));
        reservaResponde();

        extrator.extrair(List.of(), "prompt");
        extrator.extrair(List.of(), "prompt");

        verify(gemini, times(1)).gerar(eq(PRINCIPAL), any(), anyString(), anyBoolean());
    }

    @Test
    void erroTransitorioRepeteOMesmoModelo() {
        when(gemini.gerar(eq(PRINCIPAL), any(), anyString(), anyBoolean()))
                .thenThrow(new FalhaIa("API Gemini (500): interno", 500, true))
                .thenReturn(JSON_OK);

        var r = extrator.extrair(List.of(), "prompt");

        assertThat(r.modelo()).isEqualTo(PRINCIPAL);
        assertThat(r.tentativas()).isEqualTo(2);
    }

    @Test
    void principalLentoDisparaReservaEmParaleloEFicaComAPrimeira() {
        when(gemini.gerar(eq(PRINCIPAL), any(), anyString(), anyBoolean())).thenAnswer(inv -> {
            Thread.sleep(3_000); // principal "preso" na fila do Google
            return JSON_OK;
        });
        reservaResponde();
        var comReserva = novo(Clock.systemUTC(), Duration.ofMillis(200));

        long inicio = System.currentTimeMillis();
        var r = comReserva.extrair(List.of(), "prompt");

        assertThat(r.modelo()).isEqualTo(RESERVA);
        assertThat(System.currentTimeMillis() - inicio).isLessThan(2_000); // não esperou o principal
    }

    @Test
    void seAReservaFalharAindaValeORetornoDoPrincipalLento() {
        when(gemini.gerar(eq(PRINCIPAL), any(), anyString(), anyBoolean())).thenAnswer(inv -> {
            Thread.sleep(600);
            return JSON_OK;
        });
        when(gemini.gerar(eq(RESERVA), any(), anyString(), anyBoolean()))
                .thenThrow(new FalhaIa("API Gemini (403): sem acesso ao modelo", 403, false));
        var comReserva = novo(Clock.systemUTC(), Duration.ofMillis(100));

        assertThat(comReserva.extrair(List.of(), "prompt").modelo()).isEqualTo(PRINCIPAL);
    }

    @Test
    void principalRapidoNaoDisparaReserva() {
        when(gemini.gerar(eq(PRINCIPAL), any(), anyString(), anyBoolean())).thenReturn(JSON_OK);
        var comReserva = novo(Clock.systemUTC(), Duration.ofSeconds(5));

        assertThat(comReserva.extrair(List.of(), "prompt").modelo()).isEqualTo(PRINCIPAL);
        verify(gemini, never()).gerar(eq(RESERVA), any(), anyString(), anyBoolean());
    }

    @Test
    void erroDefinitivoNaoRepeteNemTrocaDeModelo() {
        principalFalha(new FalhaIa("API Gemini (403): chave inválida", 403, false));

        assertThatThrownBy(() -> extrator.extrair(List.of(), "prompt"))
                .isInstanceOf(FalhaIa.class)
                .satisfies(e -> assertThat(((FalhaIa) e).tentativas()).isEqualTo(1));
        verify(gemini, never()).gerar(eq(RESERVA), any(), anyString(), anyBoolean());
    }
}
