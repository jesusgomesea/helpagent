package br.com.rdamasio.helpagent.pdf;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;

/**
 * Desenha o resumo do orçamento por cotação: para cada item, as 3 opções (loja, anúncio, preço, página e hora do
 * print), com a escolhida em destaque. Quebra em quantas páginas A4 precisar; o carimbo de cada página é do
 * {@link PdfOrcamentoService}, igual ao dos anexos.
 */
final class PaginaResumoCotacao {

    private static final float MARGEM = 36, TOPO_LIVRE = 28 + 26, RODAPE = 44;
    private static final float X_ROTULO = 48, X_LOJA = 112, X_TITULO = 196, X_PRECO_FIM = 559;
    private static final float LINHA_OPCAO = 25, CABECALHO_ITEM = 18, ENTRE_ITENS = 10;

    private final PDDocument doc;
    private final PDFont negrito;
    private final PDFont normal;
    private final List<PDPage> paginas = new ArrayList<>();
    private PDPageContentStream cs;
    private float y;

    private PaginaResumoCotacao(PDDocument doc, PDFont negrito, PDFont normal) {
        this.doc = doc;
        this.negrito = negrito;
        this.normal = normal;
    }

    /** @return as páginas criadas, para o chamador carimbar */
    static List<PDPage> desenhar(PDDocument doc, DadosImpresso.ResumoCotacao resumo, PDFont negrito, PDFont normal)
            throws IOException {
        PaginaResumoCotacao p = new PaginaResumoCotacao(doc, negrito, normal);
        try {
            p.novaPagina();
            p.introducao();
            for (DadosImpresso.ItemCotado item : resumo.itens()) p.item(item);
        } finally {
            if (p.cs != null) p.cs.close();
        }
        return p.paginas;
    }

    private void novaPagina() throws IOException {
        if (cs != null) cs.close();
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        paginas.add(page);
        cs = new PDPageContentStream(doc, page);
        y = page.getMediaBox().getHeight() - TOPO_LIVRE;
    }

    private void introducao() throws IOException {
        cor(0.04f, 0.23f, 0.36f);
        texto(negrito, 14, MARGEM, y, "Resumo da cotação");
        y -= 16;
        cor(0.35f, 0.35f, 0.35f);
        texto(normal, 8.5f, MARGEM, y, "3 opções por item; a ESCOLHIDA é a que entra no impresso. Preços à vista coletados nas lojas online:");
        y -= 11;
        texto(normal, 8.5f, MARGEM, y, "são referência e podem mudar — confira antes da compra. Os prints de cada opção vêm a seguir, nesta ordem.");
        y -= 20;
    }

    private void item(DadosImpresso.ItemCotado item) throws IOException {
        float altura = CABECALHO_ITEM + LINHA_OPCAO * item.opcoes().size() + ENTRE_ITENS;
        if (y - altura < RODAPE) novaPagina();

        cor(0.04f, 0.23f, 0.36f);
        String cab = "Item " + item.ordem() + " · " + item.produto() + " · qtd " + item.quantidade();
        texto(negrito, 10, MARGEM, y, caber(cab, negrito, 10, X_PRECO_FIM - MARGEM));
        y -= 6;
        cs.setStrokingColor(0.8f, 0.8f, 0.8f);
        cs.moveTo(MARGEM, y);
        cs.lineTo(X_PRECO_FIM, y);
        cs.stroke();
        y -= 12;

        for (DadosImpresso.OpcaoCotada o : item.opcoes()) opcao(o);
        y -= ENTRE_ITENS;
    }

    private void opcao(DadosImpresso.OpcaoCotada o) throws IOException {
        if (o.escolhida()) {
            cs.setNonStrokingColor(1f, 0.95f, 0.82f); // âmbar claro, o mesmo tom do carimbo dos anexos
            cs.addRect(MARGEM + 4, y - 14, X_PRECO_FIM - MARGEM - 4, LINHA_OPCAO - 2);
            cs.fill();
        }
        PDFont f = o.escolhida() ? negrito : normal;
        cor(0.1f, 0.1f, 0.1f);
        texto(f, 9, X_ROTULO, y, o.escolhida() ? "ESCOLHIDA" : o.rotulo());
        texto(f, 9, X_LOJA, y, caber(o.loja(), f, 9, X_TITULO - X_LOJA - 6));
        float wPreco = f.getStringWidth(PdfOrcamentoService.somenteWinAnsi(o.preco())) / 1000 * 9;
        texto(f, 9, X_PRECO_FIM - wPreco, y, o.preco());
        texto(normal, 9, X_TITULO, y, caber(o.titulo(), normal, 9, X_PRECO_FIM - wPreco - X_TITULO - 10));
        cor(0.4f, 0.4f, 0.4f);
        String rodape = (o.capturadoEm() == null ? "" : "print " + o.capturadoEm() + " · ") + o.url();
        if (o.alerta() != null) {
            // quem valida precisa saber que a conferência automática não achou o preço no print
            cor(0.64f, 0.09f, 0.11f);
            String aviso = "CONFERIR PRINT · ";
            texto(negrito, 7, X_LOJA, y - 10, aviso);
            float w = negrito.getStringWidth(aviso) / 1000 * 7;
            cor(0.4f, 0.4f, 0.4f);
            texto(normal, 7, X_LOJA + w, y - 10, caber(rodape, normal, 7, X_PRECO_FIM - X_LOJA - w));
        } else {
            texto(normal, 7, X_LOJA, y - 10, caber(rodape, normal, 7, X_PRECO_FIM - X_LOJA));
        }
        y -= LINHA_OPCAO;
    }

    private void cor(float r, float g, float b) throws IOException {
        cs.setNonStrokingColor(r, g, b);
    }

    private void texto(PDFont fonte, float tamanho, float x, float yy, String s) throws IOException {
        cs.beginText();
        cs.setFont(fonte, tamanho);
        cs.newLineAtOffset(x, yy);
        cs.showText(PdfOrcamentoService.somenteWinAnsi(s == null ? "" : s));
        cs.endText();
    }

    /** Corta com reticências para caber na coluna — título de anúncio chega a 200 caracteres. */
    private static String caber(String s, PDFont fonte, float tamanho, float largura) throws IOException {
        String t = PdfOrcamentoService.somenteWinAnsi(s == null ? "" : s.replace('\n', ' '));
        if (fonte.getStringWidth(t) / 1000 * tamanho <= largura) return t;
        while (!t.isEmpty() && fonte.getStringWidth(t + "…") / 1000 * tamanho > largura) t = t.substring(0, t.length() - 1);
        return t + "…";
    }
}
