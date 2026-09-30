package br.com.rdamasio.helpagent.fornecedor;

import static org.assertj.core.api.Assertions.assertThat;
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
import br.com.rdamasio.helpagent.orcamento.OrcamentoItem;
import br.com.rdamasio.helpagent.orcamento.OrcamentoRepository;

/**
 * Cadastro que cresce com o uso: o fornecedor novo é cadastrado ao gerar, o nome lido pela IA vira apelido e o
 * próximo orçamento dele é reconhecido; o histórico filtra e mostra o fornecedor. Mesma configuração dos testes do
 * histórico (o Spring reaproveita o contexto).
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:lixeira;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "helpagent.seguranca.habilitada=false",
        "helpagent.gemini.api-key="
})
@AutoConfigureMockMvc
class FornecedoresTest {

    @Autowired MockMvc mvc;
    @Autowired FornecedorService service;
    @Autowired FornecedorRepository fornecedores;
    @Autowired OrcamentoRepository orcamentos;
    @Autowired LojaRepository lojas;

    @BeforeEach
    void limpar() {
        orcamentos.deleteAll();
        fornecedores.deleteAll();
    }

    @Test
    void novoECadastradoEONomeLidoViraApelido() {
        Fornecedor f = service.vincular("Infotec", "34.336.083/0001-06", "INFOTEC SOLUCOES EM INFORMATICA LTDA").orElseThrow();
        assertThat(f.getId()).isNotNull();
        assertThat(f.getApelidos()).containsExactly("INFOTEC SOLUCOES EM INFORMATICA LTDA");

        // próxima leitura, sem CNPJ, com o nome longo: reconhece o mesmo
        assertThat(service.reconhecer("Infotec Soluções em Informática Ltda.", null)).map(Fornecedor::getId).contains(f.getId());
        // e gerar de novo não duplica
        assertThat(service.vincular("INFOTEC", null, null)).map(Fornecedor::getId).contains(f.getId());
        assertThat(fornecedores.count()).isEqualTo(1);
    }

    @Test
    void historicoFiltraEMostraOFornecedor() throws Exception {
        Fornecedor f = service.vincular("Infotec", null, null).orElseThrow();
        Orcamento o = new Orcamento(ModoAquisicao.REQUISICAO, lojas.findByNumero(23).orElseThrow(), "NOBREAK", LocalDate.now(),
                null, null, null, null, new BigDecimal("500.00"), null, "A", "B", "teste", Instant.now());
        OrcamentoItem item = new OrcamentoItem(1, "Nobreak", "1500VA", BigDecimal.ONE, new BigDecimal("500.00"), new BigDecimal("500.00"));
        item.definirFornecedor(f);
        o.adicionarItem(item);
        o.registrarPdf("x.pdf", "inexistente/x.pdf");
        orcamentos.save(o);
        Orcamento outro = new Orcamento(ModoAquisicao.REQUISICAO, lojas.findByNumero(23).orElseThrow(), "MOUSE", LocalDate.now(),
                null, null, null, null, new BigDecimal("50.00"), null, "A", "B", "teste", Instant.now());
        outro.registrarPdf("y.pdf", "inexistente/y.pdf");
        orcamentos.save(outro);

        mvc.perform(get("/api/historico").param("fornecedor", String.valueOf(f.getId())))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.itens[0].fornecedores[0]").value("Infotec"));
        mvc.perform(get("/api/fornecedores").param("incluirInativos", "true"))
                .andExpect(jsonPath("$[0].nome").value("Infotec"))
                .andExpect(jsonPath("$[0].orcamentos").value(1));
    }
}
