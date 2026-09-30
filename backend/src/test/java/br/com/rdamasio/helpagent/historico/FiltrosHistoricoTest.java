package br.com.rdamasio.helpagent.historico;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import br.com.rdamasio.helpagent.loja.LojaRepository;
import br.com.rdamasio.helpagent.orcamento.ModoAquisicao;
import br.com.rdamasio.helpagent.orcamento.Orcamento;
import br.com.rdamasio.helpagent.orcamento.OrcamentoRepository;
import br.com.rdamasio.helpagent.orcamento.OrigemOrcamento;

/**
 * Filtros do painel do histórico (período de emissão, loja, faixa de valor, origem): valem na listagem e nas
 * contagens das abas, combinados com a busca. Mesma configuração do {@link LixeiraHistoricoTest} (o Spring
 * reaproveita o contexto).
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:lixeira;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "helpagent.seguranca.habilitada=false",
        "helpagent.gemini.api-key="
})
@AutoConfigureMockMvc
class FiltrosHistoricoTest {

    @Autowired MockMvc mvc;
    @Autowired OrcamentoRepository orcamentos;
    @Autowired LojaRepository lojas;

    @BeforeEach
    void tresOrcamentos() {
        orcamentos.deleteAll();
        salvar(23, ModoAquisicao.REQUISICAO, LocalDate.of(2026, 9, 10), "150.00", OrigemOrcamento.DOCUMENTOS);
        salvar(23, ModoAquisicao.CAPEX, LocalDate.of(2026, 9, 20), "4200.00", OrigemOrcamento.COTACAO);
        salvar(5, ModoAquisicao.REQUISICAO, LocalDate.of(2026, 8, 30), "900.00", OrigemOrcamento.DOCUMENTOS);
    }

    private void salvar(int loja, ModoAquisicao modo, LocalDate emissao, String total, OrigemOrcamento origem) {
        Orcamento o = new Orcamento(modo, lojas.findByNumero(loja).orElseThrow(), "ITEM " + total, emissao, null, null,
                null, null, new BigDecimal(total), null, "A", "B", "teste", Instant.now());
        o.definirOrigem(origem);
        o.registrarPdf("x.pdf", "inexistente/x.pdf");
        orcamentos.save(o);
    }

    @Test
    void periodoDeEmissao() throws Exception {
        mvc.perform(get("/api/historico").param("de", "2026-09-01").param("ate", "2026-09-30"))
                .andExpect(jsonPath("$.total").value(2));
    }

    @Test
    void lojaEValor() throws Exception {
        mvc.perform(get("/api/historico").param("loja", "23").param("valorMin", "1000"))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.itens[0].total").value(4200.00));
        mvc.perform(get("/api/historico").param("valorMax", "1000")).andExpect(jsonPath("$.total").value(2));
    }

    @Test
    void origemEContagemDasAbasRespeitamOsFiltros() throws Exception {
        mvc.perform(get("/api/historico").param("origem", "COTACAO")).andExpect(jsonPath("$.total").value(1));
        mvc.perform(get("/api/historico/contagem").param("loja", "23"))
                .andExpect(jsonPath("$.TODOS").value(2))
                .andExpect(jsonPath("$.REQUISICAO").value(1))
                .andExpect(jsonPath("$.CAPEX").value(1));
    }
}
