package br.com.rdamasio.helpagent.historico;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import br.com.rdamasio.helpagent.armazenamento.ArmazenamentoArquivos;
import br.com.rdamasio.helpagent.loja.LojaRepository;
import br.com.rdamasio.helpagent.orcamento.ModoAquisicao;
import br.com.rdamasio.helpagent.orcamento.Orcamento;
import br.com.rdamasio.helpagent.orcamento.OrcamentoRepository;

/**
 * Lixeira do histórico pela API: apagar vai para a lixeira (some das abas e do aviso de chamado já orçado),
 * restaurar devolve, excluir de vez só a partir da lixeira, e o que passou de 30 dias some sozinho.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:lixeira;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "helpagent.seguranca.habilitada=false",
        "helpagent.gemini.api-key="
})
@AutoConfigureMockMvc
class LixeiraHistoricoTest {

    @Autowired MockMvc mvc;
    @Autowired OrcamentoRepository orcamentos;
    @Autowired LojaRepository lojas;
    @Autowired ArmazenamentoArquivos armazenamento;

    private Long id;

    @BeforeEach
    void umOrcamento() {
        orcamentos.deleteAll();
        Orcamento o = new Orcamento(ModoAquisicao.REQUISICAO, lojas.findByNumero(23).orElseThrow(), "SSD PARA LOJA 23",
                LocalDate.of(2026, 9, 30), "1021069", null, null, null, new BigDecimal("300.00"), "obs", "A", "B",
                "teste", Instant.parse("2026-09-30T12:00:00Z"));
        o.registrarPdf("teste.pdf", "inexistente/teste.pdf");
        id = orcamentos.save(o).getId();
    }

    @Test
    void apagarVaiParaALixeiraERestaurarDevolve() throws Exception {
        mvc.perform(delete("/api/historico/" + id)).andExpect(status().isNoContent());

        mvc.perform(get("/api/historico/contagem"))
                .andExpect(jsonPath("$.TODOS").value(0))
                .andExpect(jsonPath("$.LIXEIRA").value(1));
        mvc.perform(get("/api/historico").param("lixeira", "true"))
                .andExpect(jsonPath("$.itens[0].id").value(id))
                .andExpect(jsonPath("$.itens[0].excluidoPor").exists());
        // chamado na lixeira não dispara o aviso de "já orçado"
        mvc.perform(get("/api/historico/por-chamado").param("numeros", "1021069")).andExpect(jsonPath("$.length()").value(0));

        mvc.perform(post("/api/historico/" + id + "/restaurar")).andExpect(status().isNoContent());
        mvc.perform(get("/api/historico/contagem"))
                .andExpect(jsonPath("$.TODOS").value(1))
                .andExpect(jsonPath("$.LIXEIRA").value(0));
    }

    @Test
    void excluirDeVezSoPelaLixeira() throws Exception {
        mvc.perform(delete("/api/historico/" + id + "/definitivo")).andExpect(status().isUnprocessableContent());
        assertThat(orcamentos.existsById(id)).isTrue();

        mvc.perform(delete("/api/historico/" + id));
        mvc.perform(delete("/api/historico/" + id + "/definitivo")).andExpect(status().isNoContent());
        assertThat(orcamentos.existsById(id)).isFalse();
    }

    @Test
    void passadoOPrazoSomeSozinho() {
        Orcamento o = orcamentos.findById(id).orElseThrow();
        o.mandarParaLixeira("teste", Instant.parse("2026-09-30T12:00:00Z"));
        orcamentos.save(o);

        var dia29 = new LixeiraHistorico(orcamentos, armazenamento,
                Clock.fixed(Instant.parse("2026-09-30T12:00:00Z").plus(Duration.ofDays(29)), ZoneOffset.UTC));
        assertThat(dia29.limparVencidos()).isZero();

        var dia31 = new LixeiraHistorico(orcamentos, armazenamento,
                Clock.fixed(Instant.parse("2026-09-30T12:00:00Z").plus(Duration.ofDays(31)), ZoneOffset.UTC));
        assertThat(dia31.limparVencidos()).isEqualTo(1);
        assertThat(orcamentos.existsById(id)).isFalse();
    }
}
