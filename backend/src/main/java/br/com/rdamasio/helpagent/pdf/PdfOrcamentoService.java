package br.com.rdamasio.helpagent.pdf;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.imageio.ImageIO;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDPageContentStream.AppendMode;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import br.com.rdamasio.helpagent.common.Documento;
import br.com.rdamasio.helpagent.config.HelpAgentProperties;
import br.com.rdamasio.helpagent.template.CamposImpresso;
import br.com.rdamasio.helpagent.template.TemplatePdfRepositorio;

/**
 * Monta o PDF final: formulário do template preenchido → anexos (chamados, depois orçamentos).
 * Porta de {@code gerarPDF}/{@code anexarUmDocumento}/{@code carimbarRotulo} do HTML v3.5 (pdf-lib → PDFBox).
 */
@Service
public class PdfOrcamentoService {

    private static final Logger log = LoggerFactory.getLogger(PdfOrcamentoService.class);
    private static final Locale PT_BR = Locale.of("pt", "BR");
    private static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter CARIMBO = DateTimeFormatter.ofPattern("dd/MM/yyyy, HH:mm:ss");
    private static final float MARGEM = 36, CABECALHO = 40, FAIXA = 28;

    private final TemplatePdfRepositorio templates;
    private final HelpAgentProperties props;

    public PdfOrcamentoService(TemplatePdfRepositorio templates, HelpAgentProperties props) {
        this.templates = templates;
        this.props = props;
    }

    public byte[] gerar(DadosImpresso d) {
        List<PDDocument> origens = new ArrayList<>(); // páginas importadas dependem da origem aberta até o save
        try (PDDocument doc = Loader.loadPDF(templates.carregar(d.template()))) {
            preencherFormulario(doc, d);

            PDFont negrito = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            PDFont normal = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (DadosImpresso.Anexo anexo : d.anexos()) {
                anexar(doc, anexo, negrito, normal, origens);
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Falha ao montar o PDF", e);
        } finally {
            for (PDDocument o : origens) {
                try { o.close(); } catch (IOException ignorado) { /* já salvo */ }
            }
        }
    }

    private void preencherFormulario(PDDocument doc, DadosImpresso d) throws IOException {
        PDAcroForm form = doc.getDocumentCatalog().getAcroForm();
        if (form == null) throw new IllegalStateException("Template " + d.template() + " não tem formulário");

        preencher(form, CamposImpresso.TITULO, d.titulo());
        preencher(form, CamposImpresso.DEPARTAMENTO, d.departamento());
        preencher(form, CamposImpresso.EMPRESA, d.empresa());
        preencher(form, CamposImpresso.CNPJ, d.cnpj());
        preencher(form, CamposImpresso.REQUERENTE, d.requerente());
        preencher(form, CamposImpresso.EMITIDO, d.dataEmissao().format(DATA));
        if (d.validade() != null) preencher(form, CamposImpresso.VALIDADE, d.validade().format(DATA));

        for (int i = 0; i < d.linhas().size() && i < CamposImpresso.LINHAS_ITENS.size(); i++) {
            CamposImpresso.LinhaItem campos = CamposImpresso.LINHAS_ITENS.get(i);
            DadosImpresso.Linha l = d.linhas().get(i);
            preencher(form, campos.ordem(), "%02d".formatted(i + 1));
            preencher(form, campos.produto(), l.produto());
            preencher(form, campos.descricao(), l.descricao());
            preencher(form, campos.qtd(), l.quantidade());
            preencher(form, campos.unitario(), l.unitario());
            preencher(form, campos.total(), l.total());
        }

        preencher(form, CamposImpresso.SUBTOTAL, d.subtotal());
        preencher(form, CamposImpresso.FRETE, d.frete());
        preencher(form, CamposImpresso.ACRESCIMOS, d.acrescimos());
        preencher(form, CamposImpresso.TOTAL, d.total());
        preencher(form, CamposImpresso.OBSERVACOES, d.observacoes());
        preencher(form, CamposImpresso.VARIACAO, d.variacao());
        preencher(form, CamposImpresso.DIA, "%02d".formatted(d.dataEmissao().getDayOfMonth()));
        preencher(form, CamposImpresso.MES, d.dataEmissao().getMonth().getDisplayName(TextStyle.FULL, PT_BR));
        preencher(form, CamposImpresso.ANO, String.valueOf(d.dataEmissao().getYear()));
        preencher(form, CamposImpresso.REQUERENTE_ASSINATURA, d.requerente());
        preencher(form, CamposImpresso.GESTOR, d.gestor());
    }

    private void preencher(PDAcroForm form, String campo, String valor) throws IOException {
        if (valor == null || valor.isBlank()) return;
        PDField f = form.getField(campo);
        if (f == null) {
            log.warn("Campo '{}' não existe no template", campo);
            return;
        }
        try {
            f.setValue(valor);
        } catch (IllegalArgumentException e) {
            // A fonte do formulário (WinAnsi) não codifica emoji e afins; melhor perder o símbolo que o campo.
            f.setValue(somenteWinAnsi(valor));
        }
    }

    private void anexar(PDDocument doc, DadosImpresso.Anexo anexo, PDFont negrito, PDFont normal,
            List<PDDocument> origens) throws IOException {
        Documento arq = anexo.documento();
        try {
            if (arq.ehPdf()) {
                PDDocument origem = Loader.loadPDF(arq.conteudo());
                origens.add(origem);
                origem.setAllSecurityToBeRemoved(true);
                PDPage primeira = null;
                for (PDPage p : origem.getPages()) {
                    PDPage importada = doc.importPage(p);
                    if (primeira == null) primeira = importada;
                }
                if (primeira != null) carimbar(doc, primeira, anexo.rotulo(), negrito, normal);
            } else if (arq.ehImagem()) {
                PDImageXObject img = imagem(doc, arq);
                PDPage page = new PDPage(PDRectangle.A4);
                doc.addPage(page);
                float maxW = page.getMediaBox().getWidth() - MARGEM * 2;
                float maxH = page.getMediaBox().getHeight() - MARGEM * 2 - CABECALHO;
                float escala = Math.min(maxW / img.getWidth(), maxH / img.getHeight());
                float w = img.getWidth() * escala, h = img.getHeight() * escala;
                float x = (page.getMediaBox().getWidth() - w) / 2;
                float y = page.getMediaBox().getHeight() - MARGEM - CABECALHO - h;
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.drawImage(img, x, y, w, h);
                }
                carimbar(doc, page, anexo.rotulo(), negrito, normal);
            } else {
                throw new IllegalArgumentException("tipo de arquivo não suportado: " + arq.mimeType());
            }
        } catch (IOException | RuntimeException e) {
            // Um anexo ruim não derruba o impresso inteiro: vira página explicando o que faltou.
            log.warn("Falha ao anexar '{}': {}", anexo.rotulo(), e.getMessage());
            paginaDeErro(doc, anexo.rotulo(), e, normal);
        }
    }

    /** PNG/JPEG/GIF/BMP direto; o resto passa pelo ImageIO e vira PNG sem perda. */
    private PDImageXObject imagem(PDDocument doc, Documento arq) throws IOException {
        try {
            return PDImageXObject.createFromByteArray(doc, arq.conteudo(), "anexo");
        } catch (IOException | IllegalArgumentException e) {
            BufferedImage bi = ImageIO.read(new ByteArrayInputStream(arq.conteudo()));
            if (bi == null) throw new IOException("formato de imagem não suportado (" + arq.mimeType() + ")", e);
            return LosslessFactory.createFromImage(doc, bi);
        }
    }

    private void carimbar(PDDocument doc, PDPage page, String rotulo, PDFont negrito, PDFont normal) throws IOException {
        PDRectangle box = page.getMediaBox();
        float w = box.getWidth(), topo = box.getUpperRightY(), esq = box.getLowerLeftX();
        String ts = ZonedDateTime.now(props.fuso()).format(CARIMBO);
        try (PDPageContentStream cs = new PDPageContentStream(doc, page, AppendMode.APPEND, true, true)) {
            cs.setNonStrokingColor(0.91f, 0.63f, 0.13f); // âmbar, igual à v3.5
            cs.addRect(esq, topo - FAIXA, w, FAIXA);
            cs.fill();
            cs.setNonStrokingColor(1f, 1f, 1f);
            texto(cs, negrito, 11, esq + 18, topo - 19, somenteWinAnsi(rotulo));
            float tsW = normal.getStringWidth(ts) / 1000 * 8;
            texto(cs, normal, 8, esq + w - tsW - 18, topo - 18, ts);
        }
    }

    private void paginaDeErro(PDDocument doc, String rotulo, Exception e, PDFont fonte) throws IOException {
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            cs.setNonStrokingColor(0.6f, 0.1f, 0.1f);
            texto(cs, fonte, 12, 50, 750, somenteWinAnsi("Não foi possível anexar: " + rotulo + "."));
            cs.setNonStrokingColor(0.4f, 0.4f, 0.4f);
            String detalhe = e.getMessage() == null ? "erro desconhecido" : e.getMessage();
            if (detalhe.length() > 110) detalhe = detalhe.substring(0, 110) + "…";
            texto(cs, fonte, 9, 50, 720, somenteWinAnsi("Detalhe técnico: " + detalhe));
        }
    }

    private static void texto(PDPageContentStream cs, PDFont fonte, float tamanho, float x, float y, String s)
            throws IOException {
        cs.beginText();
        cs.setFont(fonte, tamanho);
        cs.newLineAtOffset(x, y);
        cs.showText(s);
        cs.endText();
    }

    /** Mantém o que a codificação WinAnsi (Latin-1 + aspas/travessões) suporta. */
    static String somenteWinAnsi(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        s.codePoints().forEach(cp -> {
            if ((cp >= 0x20 && cp <= 0x7E) || (cp >= 0xA0 && cp <= 0xFF) || "–—‘’“”•…€".indexOf(cp) >= 0 || cp == '\n') {
                sb.appendCodePoint(cp);
            }
        });
        return sb.toString();
    }
}
