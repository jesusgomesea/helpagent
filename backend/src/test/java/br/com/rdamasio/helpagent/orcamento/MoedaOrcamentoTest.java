package br.com.rdamasio.helpagent.orcamento;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import br.com.rdamasio.helpagent.common.Dinheiro;
import br.com.rdamasio.helpagent.common.ErroNegocio;
import br.com.rdamasio.helpagent.common.Moeda;

/**
 * Moeda do impresso (15/10/2026): orçamento em dólar ou euro sai na moeda original, sem conversão — o símbolo vai
 * nos campos do PDF, fica gravado no orçamento e aparece no histórico. Item cotado (em R$) só em orçamento em real.
 * Os PDFs vão para {@code target/} (nada nos dados de verdade); {@code target/impresso-euro.pdf} e o PNG da 1ª página ficam
 * para conferir a olho.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:lixeira;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "helpagent.seguranca.habilitada=false",
        "helpagent.gemini.api-key=",
        "helpagent.armazenamento.diretorio=target/arquivos-teste"
})
@AutoConfigureMockMvc
class MoedaOrcamentoTest {

    @Autowired OrcamentoService service;
    @Autowired OrcamentoRepository orcamentos;
    @Autowired MockMvc mvc;

    private static GerarOrcamentoRequest pedido(Moeda moeda, GerarOrcamentoRequest.Item item) {
        return new GerarOrcamentoRequest(ModoAquisicao.CAPEX, "23", "Licença de software", LocalDate.of(2026, 10, 15),
                null, null, List.of(item), null, null, new BigDecimal("50"), "CAPEX. Licença anual.", "A", "B", null,
                moeda);
    }

    @Test
    void impressoEmEuroLevaOSimboloNosCamposEFicaGravado() throws Exception {
        var item = new GerarOrcamentoRequest.Item("Licença", "anual", new BigDecimal("2"), new BigDecimal("600"));
        var gerado = service.gerar(pedido(Moeda.EUR, item), List.of(), List.of());

        Files.createDirectories(Path.of("target"));
        Files.write(Path.of("target/impresso-euro.pdf"), gerado.pdf());
        try (PDDocument doc = Loader.loadPDF(gerado.pdf())) {
            List<String> valores = new java.util.ArrayList<>();
            for (PDField f : doc.getDocumentCatalog().getAcroForm().getFieldTree()) valores.add(f.getValueAsString());
            // € existe na fonte WinAnsi do formulário: não pode ter sido descartado
            assertThat(valores).contains("€ 600,00", "€ 1.200,00", "€ 50,00", "€ 1.250,00");
            assertThat(String.join(" ", valores)).doesNotContain("R$");
            javax.imageio.ImageIO.write(new org.apache.pdfbox.rendering.PDFRenderer(doc).renderImageWithDPI(0, 90), "png",
                    Path.of("target/impresso-euro.png").toFile());
        }
        assertThat(orcamentos.findById(gerado.id()).orElseThrow().getMoeda()).isEqualTo(Moeda.EUR);
        mvc.perform(get("/api/historico").param("busca", "Licença de software"))
                .andExpect(jsonPath("$.itens[0].moeda").value("EUR"));
    }

    @Test
    void semMoedaContinuaEmReal() throws Exception {
        var item = new GerarOrcamentoRequest.Item("Licença", null, BigDecimal.ONE, new BigDecimal("100"));
        var gerado = service.gerar(pedido(null, item), List.of(), List.of());
        assertThat(orcamentos.findById(gerado.id()).orElseThrow().getMoeda()).isEqualTo(Moeda.BRL);
    }

    @Test
    void itemCotadoSoEmReal() {
        var cotado = new GerarOrcamentoRequest.Item("SSD", null, BigDecimal.ONE, new BigDecimal("200"),
                List.of("print-1"), null, null, null);
        assertThatThrownBy(() -> service.gerar(pedido(Moeda.USD, cotado), List.of(), List.of()))
                .isInstanceOf(ErroNegocio.class).hasMessageContaining("só em orçamento em real");
    }

    @Test
    void dinheiroLeQualquerSimboloEFormataNaMoeda() {
        assertThat(Dinheiro.parse("US$ 1.200,50")).isEqualByComparingTo("1200.50");
        assertThat(Dinheiro.parse("€ 99,90")).isEqualByComparingTo("99.90");
        assertThat(Dinheiro.parse("R$ 1.234,56")).isEqualByComparingTo("1234.56");
        assertThat(Dinheiro.formatar(new BigDecimal("1200.5"), Moeda.USD)).isEqualTo("US$ 1.200,50");
        assertThat(Dinheiro.formatar(new BigDecimal("1200.5"))).isEqualTo("R$ 1.200,50");
    }
}
