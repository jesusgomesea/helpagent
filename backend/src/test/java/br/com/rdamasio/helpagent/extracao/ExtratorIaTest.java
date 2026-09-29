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

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import br.com.rdamasio.helpagent.common.Documento;
import br.com.rdamasio.helpagent.config.HelpAgentProperties;
import br.com.rdamasio.helpagent.config.HelpAgentProperties.ModeloIa;
import br.com.rdamasio.helpagent.usoia.ControleCotaIa;
import br.com.rdamasio.helpagent.usoia.Ocorrencia;
import br.com.rdamasio.helpagent.usoia.Papel;
import br.com.rdamasio.helpagent.usoia.UsoIa;
import br.com.rdamasio.helpagent.usoia.UsoIaService;
import tools.jackson.databind.json.JsonMapper;

/**
 * Política da cadeia de modelos da leitura por IA. É ela que decide quanto o usuário espera e quanta cota se gasta
 * quando o Google está lento ou sem cota — por isso cada regra do javadoc do {@link ExtratorIa} tem um teste aqui.
 */
class ExtratorIaTest {

    private static final String PRINCIPAL = "modelo-principal";
    private static final String RESERVA = "modelo-reserva";
    private static final String TERCEIRO = "modelo-terceiro";
    private static final String JSON_OK = """
            {"titulo":"Teste","itens":[{"produto":"X","qtd":"1","valor_unit":"10,00","valor_total":"10,00"}],"total":"R$ 10,00"}
            """;
    private static final GeminiClient.Geracao OK = new GeminiClient.Geracao(JSON_OK, 2000, 150, null, 10);

    private GeminiClient gemini;
    private UsoIaService uso;
    private final AtomicReference<Instant> agora = new AtomicReference<>(Instant.parse("2026-09-25T15:00:00Z"));
    private final Clock relogio = new Clock() {
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return agora.get(); }
    };
    private ControleCotaIa controle;
    private ExtratorIa extrator;

    @BeforeEach
    void preparar() {
        gemini = mock(GeminiClient.class);
        uso = mock(UsoIaService.class);
        extrator = novo(Duration.ZERO, 6, folgado(PRINCIPAL), folgado(RESERVA));
    }

    private static ModeloIa folgado(String modelo) {
        return new ModeloIa(modelo, 100, 10_000_000, 1000);
    }

    /** @param reservaApos 0 = sem reserva em paralelo (os testes de política não dependem de tempo) */
    private ExtratorIa novo(Duration reservaApos, int maxChamadas, ModeloIa... cadeia) {
        var cfg = new HelpAgentProperties.Gemini("chave", "http://x", List.of(cadeia), "", 3, Duration.ZERO, 1000,
                Duration.ofSeconds(30), "low", Duration.ZERO, reservaApos, 3, Duration.ofSeconds(5), maxChamadas);
        var props = new HelpAgentProperties(cfg, null, new HelpAgentProperties.Armazenamento(Path.of(".")),
                new HelpAgentProperties.Seguranca(false), null, ZoneId.of("America/Sao_Paulo"), List.of());
        controle = new ControleCotaIa(List.of(cadeia), relogio);
        return new ExtratorIa(gemini, new RespostaIaParser(JsonMapper.builder().build()), props, controle, uso);
    }

    private void falha(String modelo, FalhaIa erro) {
        when(gemini.gerar(eq(modelo), any(), anyString(), anyBoolean())).thenThrow(erro);
    }

    private void responde(String modelo) {
        when(gemini.gerar(eq(modelo), any(), anyString(), anyBoolean())).thenReturn(OK);
    }

    private int chamadas(String modelo) {
        return org.mockito.Mockito.mockingDetails(gemini).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("gerar") && modelo.equals(i.getArgument(0))).toList().size();
    }

    @Test
    void caminhoFelizUsaSoOPrincipal() {
        responde(PRINCIPAL);

        var r = extrator.extrair(List.of(), "prompt");

        assertThat(r.modelo()).isEqualTo(PRINCIPAL);
        assertThat(r.degrau()).isZero();
        assertThat(r.tentativas()).isEqualTo(1);
        verify(gemini, never()).gerar(eq(RESERVA), any(), anyString(), anyBoolean());
    }

    @Test
    void sobrecargaNoPrincipalVaiDiretoParaReservaSemRepetir() {
        falha(PRINCIPAL, FalhaIa.sobrecarga("API Gemini (503): high demand", 503, null));
        responde(RESERVA);

        var r = extrator.extrair(List.of(), "prompt");

        assertThat(r.modelo()).isEqualTo(RESERVA);
        assertThat(r.degrau()).isEqualTo(1);
        assertThat(r.tentativas()).isEqualTo(2);
        verify(gemini, times(1)).gerar(eq(PRINCIPAL), any(), anyString(), anyBoolean());
    }

    @Test
    void cadeiaDesceDegrauPorDegrauAteQuemTemCota() {
        extrator = novo(Duration.ZERO, 6, folgado(PRINCIPAL), folgado(RESERVA), folgado(TERCEIRO));
        falha(PRINCIPAL, FalhaIa.cotaDiaria("API Gemini (429): per day"));
        falha(RESERVA, FalhaIa.cotaPorMinuto("API Gemini (429): per minute", Duration.ofSeconds(10)));
        responde(TERCEIRO);

        var r = extrator.extrair(List.of(), "prompt");

        assertThat(r.modelo()).isEqualTo(TERCEIRO);
        assertThat(r.degrau()).isEqualTo(2);
        assertThat(r.tentativas()).isEqualTo(3);
    }

    @Test
    void voltaASubirQuandoAPausaDoModeloDeCimaAcaba() {
        when(gemini.gerar(eq(PRINCIPAL), any(), anyString(), anyBoolean()))
                .thenThrow(FalhaIa.cotaPorMinuto("API Gemini (429): per minute", Duration.ofSeconds(20)))
                .thenReturn(OK);
        responde(RESERVA);

        assertThat(extrator.extrair(List.of(), "prompt").modelo()).isEqualTo(RESERVA);
        agora.set(agora.get().plusSeconds(10));
        assertThat(extrator.extrair(List.of(), "prompt").modelo()).isEqualTo(RESERVA); // ainda descansando
        agora.set(agora.get().plusSeconds(15));
        assertThat(extrator.extrair(List.of(), "prompt").modelo()).isEqualTo(PRINCIPAL); // voltou ao topo sozinho
    }

    @Test
    void limiteLocalDesceSemChamarOModelo() {
        // plano do principal: 1 requisição por minuto. A segunda leitura no mesmo minuto nem chama o principal
        extrator = novo(Duration.ZERO, 6, new ModeloIa(PRINCIPAL, 1, 10_000_000, 1000), folgado(RESERVA));
        responde(PRINCIPAL);
        responde(RESERVA);

        assertThat(extrator.extrair(List.of(), "prompt").modelo()).isEqualTo(PRINCIPAL);
        assertThat(extrator.extrair(List.of(), "prompt").modelo()).isEqualTo(RESERVA);
        assertThat(chamadas(PRINCIPAL)).isEqualTo(1);

        agora.set(agora.get().plusSeconds(61));
        assertThat(extrator.extrair(List.of(), "prompt").modelo()).isEqualTo(PRINCIPAL);
    }

    @Test
    void semNenhumModeloComCotaNaoChamaNadaEAvisa() {
        extrator = novo(Duration.ZERO, 6, new ModeloIa(PRINCIPAL, 1, 10_000_000, 1), new ModeloIa(RESERVA, 1, 10_000_000, 1));
        responde(PRINCIPAL);
        when(gemini.gerar(eq(RESERVA), any(), anyString(), anyBoolean())).thenReturn(OK);
        extrator.extrair(List.of(), "prompt");
        extrator.extrair(List.of(), "prompt");

        assertThatThrownBy(() -> extrator.extrair(List.of(), "prompt"))
                .isInstanceOf(FalhaIa.class)
                .hasMessageContaining("Nenhum modelo de IA com cota");
        assertThat(chamadas(PRINCIPAL) + chamadas(RESERVA)).isEqualTo(2);
    }

    @Test
    void casoReal28deSetembroAlternaDeVoltaParaOPrincipal() {
        // principal: 503; reserva: trava (30 s); principal, logo depois, responde em 2–3 s
        when(gemini.gerar(eq(PRINCIPAL), any(), anyString(), anyBoolean()))
                .thenThrow(FalhaIa.sobrecarga("API Gemini (503): high demand", 503, null))
                .thenReturn(OK);
        falha(RESERVA, FalhaIa.sobrecarga("A IA não respondeu em 30 s", 0, null));

        var r = extrator.extrair(List.of(), "prompt");

        assertThat(r.modelo()).isEqualTo(PRINCIPAL);
        verify(gemini, times(1)).gerar(eq(RESERVA), any(), anyString(), anyBoolean()); // não insistiu 3x na reserva
        verify(gemini, times(2)).gerar(eq(PRINCIPAL), any(), anyString(), anyBoolean());
    }

    @Test
    void sobrecargaFazAsProximasLeiturasPreferiremODegrauDeBaixo() {
        when(gemini.gerar(eq(PRINCIPAL), any(), anyString(), anyBoolean()))
                .thenThrow(FalhaIa.sobrecarga("A IA não respondeu em 30 s", 0, null))
                .thenReturn(OK);
        responde(RESERVA);

        extrator.extrair(List.of(), "prompt");
        // 20 s depois: o principal ainda "esfria" — a leitura não espera mais 30 s nele
        agora.set(agora.get().plusSeconds(20));
        assertThat(extrator.extrair(List.of(), "prompt").modelo()).isEqualTo(RESERVA);
        agora.set(agora.get().plus(ControleCotaIa.RESFRIAMENTO));
        assertThat(extrator.extrair(List.of(), "prompt").modelo()).isEqualTo(PRINCIPAL);
    }

    @Test
    void semPrincipalDisponivelNaoAlternaParaEle() {
        // principal sem cota: a alternância não pode mandar de volta para ele
        falha(PRINCIPAL, FalhaIa.cotaDiaria("API Gemini (429): You exceeded your current quota"));
        when(gemini.gerar(eq(RESERVA), any(), anyString(), anyBoolean()))
                .thenThrow(FalhaIa.sobrecarga("A IA não respondeu em 30 s", 0, null))
                .thenReturn(OK);

        assertThat(extrator.extrair(List.of(), "prompt").modelo()).isEqualTo(RESERVA);
        verify(gemini, times(1)).gerar(eq(PRINCIPAL), any(), anyString(), anyBoolean());
    }

    @Test
    void quotaDiariaTiraOPrincipalPorUmTempo() {
        falha(PRINCIPAL, new FalhaIa("API Gemini (429): Quota exceeded for metric generate_requests_per_day", 429, true));
        responde(RESERVA);

        extrator.extrair(List.of(), "prompt");
        extrator.extrair(List.of(), "prompt");

        // a segunda extração do dia nem tenta o principal
        verify(gemini, times(1)).gerar(eq(PRINCIPAL), any(), anyString(), anyBoolean());
        verify(gemini, times(2)).gerar(eq(RESERVA), any(), anyString(), anyBoolean());
    }

    @Test
    void passadaAPausaOPrincipalVoltaASerSondado() {
        falha(PRINCIPAL, new FalhaIa("API Gemini (429): quota per day", 429, true));
        responde(RESERVA);

        extrator.extrair(List.of(), "prompt");
        agora.set(agora.get().plus(ControleCotaIa.PAUSA_COTA_DIA).minusSeconds(60));
        extrator.extrair(List.of(), "prompt"); // ainda na pausa: nem tenta
        verify(gemini, times(1)).gerar(eq(PRINCIPAL), any(), anyString(), anyBoolean());

        agora.set(agora.get().plusSeconds(120));
        extrator.extrair(List.of(), "prompt"); // pausa acabou: sonda de novo
        verify(gemini, times(2)).gerar(eq(PRINCIPAL), any(), anyString(), anyBoolean());
    }

    @Test
    void limitePorMinutoDesceMasNaoTiraOPrincipalPeloDia() {
        // mensagem real do 429 por minuto: tem "Quota" e "free_tier", mas não é o limite do dia
        when(gemini.gerar(eq(PRINCIPAL), any(), anyString(), anyBoolean()))
                .thenThrow(new FalhaIa("API Gemini (429): Quota exceeded for metric: "
                        + "generate_content_free_tier_requests, limit: 5 per minute", 429, true))
                .thenReturn(OK);
        responde(RESERVA);

        // não repete o principal 1,2 s depois (levaria outro 429): desce na hora
        assertThat(extrator.extrair(List.of(), "prompt").modelo()).isEqualTo(RESERVA);
        verify(gemini, times(1)).gerar(eq(PRINCIPAL), any(), anyString(), anyBoolean());

        agora.set(agora.get().plus(ControleCotaIa.PAUSA_COTA_MINUTO).plusSeconds(1));
        assertThat(extrator.extrair(List.of(), "prompt").modelo()).isEqualTo(PRINCIPAL); // 1 min, não 15
    }

    @Test
    void cotaDiariaVindaDoCampoEstruturadoMarcaEsgotado() {
        falha(PRINCIPAL, FalhaIa.cotaDiaria("API Gemini (429): You exceeded your current quota"));
        responde(RESERVA);

        extrator.extrair(List.of(), "prompt");
        extrator.extrair(List.of(), "prompt");

        verify(gemini, times(1)).gerar(eq(PRINCIPAL), any(), anyString(), anyBoolean());
    }

    @Test
    void modeloInexistenteSaiDaCadeia() {
        falha(PRINCIPAL, FalhaIa.modeloIndisponivel("API Gemini (404): model not found"));
        responde(RESERVA);

        extrator.extrair(List.of(), "prompt");
        agora.set(agora.get().plus(Duration.ofHours(2)));
        extrator.extrair(List.of(), "prompt");

        verify(gemini, times(1)).gerar(eq(PRINCIPAL), any(), anyString(), anyBoolean());
    }

    @Test
    void erroTransitorioRepeteOMesmoModelo() {
        when(gemini.gerar(eq(PRINCIPAL), any(), anyString(), anyBoolean()))
                .thenThrow(new FalhaIa("API Gemini (500): interno", 500, true))
                .thenReturn(OK);

        var r = extrator.extrair(List.of(), "prompt");

        assertThat(r.modelo()).isEqualTo(PRINCIPAL);
        assertThat(r.tentativas()).isEqualTo(2);
    }

    @Test
    void erroTransitorioQuePersisteDesceDeDegrau() {
        falha(PRINCIPAL, new FalhaIa("API Gemini (500): interno", 500, true));
        responde(RESERVA);

        var r = extrator.extrair(List.of(), "prompt");

        assertThat(r.modelo()).isEqualTo(RESERVA);
        assertThat(r.tentativas()).isEqualTo(4); // 3 no principal (maxTentativas) + 1
    }

    @Test
    void tetoDeChamadasPorLeitura() {
        extrator = novo(Duration.ZERO, 2, folgado(PRINCIPAL), folgado(RESERVA), folgado(TERCEIRO));
        falha(PRINCIPAL, FalhaIa.sobrecarga("API Gemini (503)", 503, null));
        falha(RESERVA, FalhaIa.sobrecarga("API Gemini (503)", 503, null));
        responde(TERCEIRO);

        assertThatThrownBy(() -> extrator.extrair(List.of(), "prompt"))
                .isInstanceOf(FalhaIa.class)
                .satisfies(e -> assertThat(((FalhaIa) e).tentativas()).isEqualTo(2));
        verify(gemini, never()).gerar(eq(TERCEIRO), any(), anyString(), anyBoolean());
    }

    @Test
    void principalLentoDisparaReservaEmParaleloEFicaComAPrimeira() {
        when(gemini.gerar(eq(PRINCIPAL), any(), anyString(), anyBoolean())).thenAnswer(inv -> {
            Thread.sleep(3_000); // principal "preso" na fila do Google
            return OK;
        });
        responde(RESERVA);
        var comReserva = novo(Duration.ofMillis(200), 6, folgado(PRINCIPAL), folgado(RESERVA));

        long inicio = System.currentTimeMillis();
        var r = comReserva.extrair(List.of(), "prompt");

        assertThat(r.modelo()).isEqualTo(RESERVA);
        assertThat(System.currentTimeMillis() - inicio).isLessThan(2_000); // não esperou o principal
    }

    @Test
    void reservaEmParaleloValeTambemDepoisDeDescer() {
        // caso de 29/09/2026: 3.6 deu 503 rápido, 3.5-flash travou 30 s; o 3º deve entrar sem esperar o travado
        falha(PRINCIPAL, FalhaIa.sobrecarga("API Gemini (503)", 503, null));
        when(gemini.gerar(eq(RESERVA), any(), anyString(), anyBoolean())).thenAnswer(inv -> {
            Thread.sleep(3_000);
            return OK;
        });
        responde(TERCEIRO);
        var ext = novo(Duration.ofMillis(200), 6, folgado(PRINCIPAL), folgado(RESERVA), folgado(TERCEIRO));

        long inicio = System.currentTimeMillis();
        var r = ext.extrair(List.of(), "prompt");

        assertThat(r.modelo()).isEqualTo(TERCEIRO);
        assertThat(System.currentTimeMillis() - inicio).isLessThan(2_000);
    }

    @Test
    void reservaEmParaleloSoSeODegrauDeBaixoTiverCota() {
        when(gemini.gerar(eq(PRINCIPAL), any(), anyString(), anyBoolean())).thenAnswer(inv -> {
            Thread.sleep(500);
            return OK;
        });
        responde(RESERVA);
        var semVaga = novo(Duration.ofMillis(100), 6, folgado(PRINCIPAL), new ModeloIa(RESERVA, 5, 10_000_000, 0));

        assertThat(semVaga.extrair(List.of(), "prompt").modelo()).isEqualTo(PRINCIPAL); // esperou o lento
        verify(gemini, never()).gerar(eq(RESERVA), any(), anyString(), anyBoolean());
    }

    @Test
    void seAReservaFalharAindaValeORetornoDoPrincipalLento() {
        when(gemini.gerar(eq(PRINCIPAL), any(), anyString(), anyBoolean())).thenAnswer(inv -> {
            Thread.sleep(600);
            return OK;
        });
        falha(RESERVA, new FalhaIa("API Gemini (403): sem acesso ao modelo", 403, false));
        var comReserva = novo(Duration.ofMillis(100), 6, folgado(PRINCIPAL), folgado(RESERVA));

        assertThat(comReserva.extrair(List.of(), "prompt").modelo()).isEqualTo(PRINCIPAL);
    }

    @Test
    void principalRapidoNaoDisparaReserva() {
        responde(PRINCIPAL);
        var comReserva = novo(Duration.ofSeconds(5), 6, folgado(PRINCIPAL), folgado(RESERVA));

        assertThat(comReserva.extrair(List.of(), "prompt").modelo()).isEqualTo(PRINCIPAL);
        verify(gemini, never()).gerar(eq(RESERVA), any(), anyString(), anyBoolean());
    }

    @Test
    void erroDefinitivoNaoRepeteNemTrocaDeModelo() {
        falha(PRINCIPAL, new FalhaIa("API Gemini (403): chave inválida", 403, false));

        assertThatThrownBy(() -> extrator.extrair(List.of(), "prompt"))
                .isInstanceOf(FalhaIa.class)
                .satisfies(e -> assertThat(((FalhaIa) e).tentativas()).isEqualTo(1));
        verify(gemini, never()).gerar(eq(RESERVA), any(), anyString(), anyBoolean());
    }

    @Test
    void cadaRequisicaoVaiParaORegistroComPapelEResultado() {
        falha(PRINCIPAL, FalhaIa.sobrecarga("API Gemini (503)", 503, null));
        responde(RESERVA);

        extrator.extrair(List.of(), "prompt", new ExtratorIa.Contexto("CAPEX", "10.0.0.7"));

        ArgumentCaptor<UsoIa> registro = ArgumentCaptor.forClass(UsoIa.class);
        verify(uso, times(2)).registrar(registro.capture());
        UsoIa primeira = registro.getAllValues().get(0), segunda = registro.getAllValues().get(1);
        assertThat(primeira.getModelo()).isEqualTo(PRINCIPAL);
        assertThat(primeira.getPapel()).isEqualTo(Papel.PRINCIPAL);
        assertThat(primeira.getOcorrencia()).isEqualTo(Ocorrencia.SOBRECARGA);
        assertThat(segunda.getPapel()).isEqualTo(Papel.DEGRAU);
        assertThat(segunda.getOcorrencia()).isEqualTo(Ocorrencia.OK);
        assertThat(segunda.getTokensEntrada()).isEqualTo(2000);
        assertThat(segunda.getOrigem()).isEqualTo("10.0.0.7");
        assertThat(segunda.getLeitura()).isEqualTo(primeira.getLeitura());
    }

    @Test
    void estimativaContaPromptEPaginasDoPdf() {
        String pdf = "%PDF-1.4 1 0 obj << /Type /Pages /Count 3 >> 2 0 obj << /Type /Page >> 3 0 obj << /Type/Page >>"
                + " 4 0 obj << /Type /Page /Parent 1 0 R >>";
        var doc = new Documento("application/pdf", pdf.getBytes(StandardCharsets.ISO_8859_1));
        var img = new Documento("image/png", new byte[10]);

        assertThat(ExtratorIa.paginas(doc)).isEqualTo(3);
        assertThat(ExtratorIa.estimarTokens(List.of(doc, img), "x".repeat(3500)))
                .isEqualTo(1000 + 4 * ExtratorIa.TOKENS_POR_ARQUIVO);
    }
}
