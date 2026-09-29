package br.com.rdamasio.helpagent.pdf;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import br.com.rdamasio.helpagent.common.Documento;
import br.com.rdamasio.helpagent.config.HelpAgentProperties;
import br.com.rdamasio.helpagent.cotacao.CarimboPrintAcesso;
import br.com.rdamasio.helpagent.template.TemplateCodigo;
import br.com.rdamasio.helpagent.template.TemplatePdfRepositorio;

/**
 * Orçamento por cotação no PDF: formulário → resumo das 3 opções por item → prints, cada um com o rótulo da opção.
 * Grava {@code target/resumo-cotacao.pdf} e a página do resumo em PNG, para conferir o visual a olho.
 */
class ResumoCotacaoPdfTest {

    @Test
    void resumoVemAntesDosPrintsComAEscolhidaMarcada() throws Exception {
        var props = new HelpAgentProperties(null, null, null, null, null, ZoneId.of("America/Sao_Paulo"), List.of());
        var servico = new PdfOrcamentoService(new TemplatePdfRepositorio(), props);

        List<DadosImpresso.ItemCotado> itens = new ArrayList<>();
        List<DadosImpresso.Apendice> anexos = new ArrayList<>();
        List<DadosImpresso.Anexo> prints = new ArrayList<>();
        String[][] lojas = { { "Kabum", "Pichau", "Terabyte" }, { "Pichau", "Kabum", "Amazon" } };
        for (int i = 0; i < 2; i++) {
            List<DadosImpresso.OpcaoCotada> opcoes = new ArrayList<>();
            for (int j = 0; j < 3; j++) {
                String rotulo = j == 0 ? "Escolhida" : "Opção " + (j + 1);
                opcoes.add(new DadosImpresso.OpcaoCotada(rotulo, j == 0, lojas[i][j],
                        "SSD Kingston A400 480GB SATA III 2,5\" Leitura 500MB/s Gravação 450MB/s — anúncio de título bem comprido",
                        "R$ 2" + j + "9,90", "https://www.exemplo.com.br/produto/" + (1000 + j) + "/ssd-kingston-a400-480gb",
                        "29/09/2026 10:1" + j + ":00"));
                prints.add(new DadosImpresso.Anexo(new Documento("image/jpeg", printFalso(lojas[i][j])),
                        "Item 0" + (i + 1) + " · " + (j == 0 ? "ESCOLHIDA" : rotulo) + " · " + lojas[i][j], true));
            }
            itens.add(new DadosImpresso.ItemCotado("0" + (i + 1), i == 0 ? "SSD 480GB" : "Mouse USB", "2", opcoes));
        }
        anexos.add(new DadosImpresso.ResumoCotacao(itens));
        anexos.addAll(prints);

        byte[] pdf = servico.gerar(new DadosImpresso(TemplateCodigo.RDAM, "SSD e mouse", "TECNOLOGIA", "R DAMASIO",
                "34.336.083/0001-06", "ANTONIO", "GILSON", LocalDate.of(2026, 9, 29), null,
                List.of(new DadosImpresso.Linha("SSD 480GB", "Kabum", "2", "R$ 209,90", "R$ 419,80")),
                null, null, null, "R$ 419,80", "# 1021069. Teste", "6%", anexos));

        Files.createDirectories(Path.of("target"));
        Files.write(Path.of("target/resumo-cotacao.pdf"), pdf);
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            int formulario = Loader.loadPDF(new TemplatePdfRepositorio().carregar(TemplateCodigo.RDAM)).getNumberOfPages();
            assertThat(doc.getNumberOfPages()).isEqualTo(formulario + 1 + 6);

            PDFTextStripper t = new PDFTextStripper();
            t.setStartPage(formulario + 1);
            t.setEndPage(formulario + 1);
            String resumo = t.getText(doc);
            assertThat(resumo).contains("Resumo da cotação", "Item 01 · SSD 480GB · qtd 2", "ESCOLHIDA", "Opção 2",
                    "Terabyte", "R$ 209,90", "print 29/09/2026 10:10:00");

            t.setStartPage(formulario + 2);
            t.setEndPage(formulario + 2);
            assertThat(t.getText(doc)).contains("Item 01 · ESCOLHIDA · Kabum");
            t.setStartPage(formulario + 7);
            t.setEndPage(formulario + 7);
            assertThat(t.getText(doc)).contains("Item 02 · Opção 3 · Amazon");

            ImageIO.write(new PDFRenderer(doc).renderImageWithDPI(formulario, 80), "png",
                    Path.of("target/resumo-cotacao.png").toFile());
            ImageIO.write(new PDFRenderer(doc).renderImageWithDPI(formulario + 1, 80), "png",
                    Path.of("target/resumo-cotacao-print.png").toFile());
        }
    }

    @Test
    void carimboAcrescentaFaixaESaiEmJpeg() throws Exception {
        byte[] jpg = CarimboPrintAcesso.aplicar(pngDeTeste(1440, 900), "Kabum · capturado em 29/09/2026 10:00:00",
                "https://www.kabum.com.br/produto/613517/x");
        BufferedImage bi = ImageIO.read(new ByteArrayInputStream(jpg));
        assertThat(bi.getWidth()).isEqualTo(1440);
        assertThat(bi.getHeight()).isEqualTo(900 + 58);
        assertThat(jpg[0] & 0xFF).isEqualTo(0xFF); // cabeçalho JPEG (FF D8)
        assertThat(jpg[1] & 0xFF).isEqualTo(0xD8);
    }

    private static byte[] printFalso(String loja) throws Exception {
        return CarimboPrintAcesso.aplicar(pngDeTeste(1440, 900), loja + " · capturado em 29/09/2026 10:00:00",
                "https://www.exemplo.com.br/produto/1");
    }

    private static byte[] pngDeTeste(int w, int h) throws Exception {
        BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = bi.createGraphics();
        g.setColor(new Color(0xEE, 0xF2, 0xF6));
        g.fillRect(0, 0, w, h);
        g.setColor(Color.DARK_GRAY);
        g.drawString("página de produto de teste · R$ 209,90", 60, 120);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(bi, "png", out);
        return out.toByteArray();
    }
}
