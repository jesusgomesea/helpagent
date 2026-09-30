package br.com.rdamasio.helpagent.qualidadeia;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import br.com.rdamasio.helpagent.extracao.DadosExtraidos;
import br.com.rdamasio.helpagent.extracao.LeituraConcluida;
import br.com.rdamasio.helpagent.loja.LojaRepository;
import br.com.rdamasio.helpagent.orcamento.GerarOrcamentoRequest;
import br.com.rdamasio.helpagent.orcamento.ModoAquisicao;
import br.com.rdamasio.helpagent.orcamento.Orcamento;
import br.com.rdamasio.helpagent.orcamento.OrcamentoGerado;
import br.com.rdamasio.helpagent.orcamento.OrcamentoRepository;

/**
 * Fluxo completo pelos eventos: a leitura é guardada, o orçamento gerado a partir dela é comparado DEPOIS do commit
 * e o relatório mostra a correção. Mesma configuração dos testes do histórico (o Spring reaproveita o contexto).
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:lixeira;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "helpagent.seguranca.habilitada=false",
        "helpagent.gemini.api-key="
})
@AutoConfigureMockMvc
class QualidadeIaFluxoTest {

    @Autowired MockMvc mvc;
    @Autowired ApplicationEventPublisher eventos;
    @Autowired TransactionTemplate tx;
    @Autowired OrcamentoRepository orcamentos;
    @Autowired LojaRepository lojas;
    @Autowired CorrecaoIaRepository correcoes;
    @Autowired LeituraIaRepository leituras;

    @Test
    void leituraOrcamentoERelatorio() throws Exception {
        correcoes.deleteAll();
        leituras.deleteAll();
        var lido = new DadosExtraidos("1021069", "23", "DAMASIO PE", "Nobreak",
                List.of(new DadosExtraidos.Item("Nobreak", "1500VA", "1", "1.500,00", "1.500,00", "pág. 1", "Infotec", "")),
                "R$ 1.500,00", "# 1021069. Nobreak.", "", "");
        eventos.publishEvent(new LeituraConcluida("leitura-fluxo", Instant.now(), "REQUISICAO", "gemini-teste", 0, 1, lido));
        assertThat(leituras.existsById("leitura-fluxo")).isTrue();

        Orcamento o = new Orcamento(ModoAquisicao.REQUISICAO, lojas.findByNumero(23).orElseThrow(), "Nobreak",
                LocalDate.now(), "1021069", null, null, null, new BigDecimal("1350.00"), null, "A", "B", "teste", Instant.now());
        o.registrarPdf("x.pdf", "inexistente/x.pdf");
        Long id = orcamentos.save(o).getId();
        var confirmado = new GerarOrcamentoRequest(ModoAquisicao.REQUISICAO, "23", "Nobreak", LocalDate.now(), null,
                "1021069", List.of(new GerarOrcamentoRequest.Item("Nobreak", "1500VA", BigDecimal.ONE, new BigDecimal("1350.00"))),
                null, null, null, "# 1021069. Nobreak.", "A", "B", "leitura-fluxo");

        // dentro de uma transação: o listener só roda no commit, como no OrcamentoService
        tx.executeWithoutResult(s -> eventos.publishEvent(
                new OrcamentoGerado(id, "leitura-fluxo", confirmado, new BigDecimal("1350.00"), List.of("Infotec"))));

        assertThat(correcoes.findAll()).anyMatch(c -> c.getCampo().equals("valor_unitario") && c.isCorrigido());
        mvc.perform(get("/api/qualidade-ia"))
                .andExpect(jsonPath("$.orcamentosComparados").value(1))
                .andExpect(jsonPath("$.semCorrecao").value(0))
                .andExpect(jsonPath("$.porModelo[0].nome").value("gemini-teste"))
                .andExpect(jsonPath("$.porFornecedor[0].nome").value("Infotec"))
                .andExpect(jsonPath("$.ultimas[0].lido").exists());
    }
}
