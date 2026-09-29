package br.com.rdamasio.helpagent.cotacao;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Iterator;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/**
 * Faixa gravada em cima do print da página de produto: loja, momento da captura e endereço. O print do Chrome não
 * tem barra de endereço, e a validação manual precisa saber de onde e quando o preço veio — por isso a informação
 * vai na própria imagem, e não só no carimbo do PDF (que marca a hora em que o PDF foi montado).
 *
 * <p>Sai em JPEG: um orçamento de 10 itens leva 30 prints, e em PNG o PDF passaria de 10 MB.
 */
final class CarimboPrint {

    private static final int FAIXA = 58;
    private static final Color MARINHO = new Color(0x0B, 0x3A, 0x5C);

    private CarimboPrint() {
    }

    /**
     * @param titulo "Kabum · capturado em 28/09/2026 21:40:12"
     * @param url    endereço da página (cortado se não couber)
     */
    static byte[] aplicar(byte[] imagem, String titulo, String url) {
        BufferedImage origem = ler(imagem);
        int w = Math.max(origem.getWidth(), 900);
        BufferedImage saida = new BufferedImage(w, origem.getHeight() + FAIXA, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = saida.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, w, saida.getHeight());
            g.setColor(MARINHO);
            g.fillRect(0, 0, w, FAIXA);
            g.setColor(Color.WHITE);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 17));
            g.drawString(titulo, 16, 24);
            g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));
            g.drawString(caber(url, g.getFontMetrics(), w - 32), 16, 46);
            g.drawImage(origem, 0, FAIXA, null);
        } finally {
            g.dispose();
        }
        return jpeg(saida);
    }

    private static BufferedImage ler(byte[] imagem) {
        try {
            BufferedImage bi = ImageIO.read(new ByteArrayInputStream(imagem));
            if (bi == null) throw new IllegalArgumentException("formato de imagem não suportado (use PNG ou JPEG)");
            return bi;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String caber(String s, FontMetrics fm, int largura) {
        if (fm.stringWidth(s) <= largura) return s;
        String r = s;
        while (!r.isEmpty() && fm.stringWidth(r + "…") > largura) r = r.substring(0, r.length() - 1);
        return r + "…";
    }

    private static byte[] jpeg(BufferedImage img) {
        Iterator<ImageWriter> it = ImageIO.getImageWritersByFormatName("jpeg");
        ImageWriter w = it.next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (MemoryCacheImageOutputStream ios = new MemoryCacheImageOutputStream(out)) {
            w.setOutput(ios);
            ImageWriteParam p = w.getDefaultWriteParam();
            p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            p.setCompressionQuality(0.85f); // texto do preço continua nítido; ~150 KB por print
            w.write(null, new IIOImage(img, null, null), p);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            w.dispose();
        }
        return out.toByteArray();
    }
}
