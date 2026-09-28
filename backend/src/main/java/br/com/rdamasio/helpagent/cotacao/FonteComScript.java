package br.com.rdamasio.helpagent.cotacao;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitForSelectorState;

/**
 * Base das lojas cuja extração é um JavaScript que roda dentro da página ({@code resources/cotacao/extrair-<id>.js}).
 * O script é o ponto que quebra quando o site muda: fica isolado, um por loja, com os seletores comentados.
 *
 * <p>O script é uma função sem argumentos que devolve uma lista de objetos com as chaves snake_case de
 * {@link Anuncio#doJs} ({@code titulo, preco, preco_de, nota, vendidos, full, loja_oficial, frete_gratis,
 * recondicionado, internacional, pais, vendedor, url, patrocinado, fonte, vendedor_proprio}).
 */
public abstract class FonteComScript implements FonteCotacao {

    private final String id;
    private final String nome;
    private final Grupo grupo;
    private final String seletorPronto;
    private final String script;

    /**
     * @param seletorPronto elemento que indica que a lista já está na página (sites em JavaScript montam depois do
     *                      HTML). Se não aparecer no prazo, a página é tratada como "sem resultados".
     */
    protected FonteComScript(String id, String nome, Grupo grupo, String seletorPronto) {
        this.id = id;
        this.nome = nome;
        this.grupo = grupo;
        this.seletorPronto = seletorPronto;
        this.script = carregarScript("/cotacao/extrair-" + id + ".js");
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String nome() {
        return nome;
    }

    @Override
    public Grupo grupo() {
        return grupo;
    }

    @Override
    public List<Anuncio> extrair(Page aba) {
        try {
            // o coletor navega sem esperar a página (para abrir as lojas em paralelo): primeiro o HTML inteiro...
            aba.waitForLoadState(LoadState.DOMCONTENTLOADED, new Page.WaitForLoadStateOptions().setTimeout(45_000));
            // ...depois a lista. ATTACHED e não VISIBLE: o marcador pode ser um <script> (Kabum), que nunca fica visível
            aba.waitForSelector(seletorPronto, new Page.WaitForSelectorOptions()
                    .setState(WaitForSelectorState.ATTACHED).setTimeout(30_000));
        } catch (PlaywrightException semLista) {
            return List.of(); // sem resultados para o termo — ou verificação anti-robô (ver MANUTENCAO §7)
        }
        List<Anuncio> anuncios = new ArrayList<>();
        if (aba.evaluate(script) instanceof List<?> lista) {
            for (Object o : lista) {
                if (o instanceof Map<?, ?> m) anuncios.add(Anuncio.doJs(m));
            }
        }
        return anuncios;
    }

    /** Termo para a query string ("ssd 256gb" → "ssd+256gb"). */
    protected static String query(String termo) {
        return URLEncoder.encode(termo.strip(), StandardCharsets.UTF_8);
    }

    /** Termo em forma de caminho: minúsculas, sem acento nem pontuação, espaço vira hífen ("SSD 256GB" → "ssd-256gb"). */
    protected static String slug(String termo) {
        String s = Normalizer.normalize(termo.toLowerCase(Locale.ROOT), Normalizer.Form.NFKD).replaceAll("\\p{M}", "");
        s = s.replaceAll("[^\\p{L}\\p{N}_\\s-]", "");
        return s.strip().replaceAll("[\\s_]+", "-");
    }

    /**
     * Lê o script tirando as linhas de comentário do topo: o Playwright avalia o texto como expressão, e um
     * comentário antes da função pode fazer ele devolver undefined em vez de chamá-la.
     */
    static String carregarScript(String recurso) {
        try (InputStream in = FonteComScript.class.getResourceAsStream(recurso)) {
            if (in == null) throw new IllegalStateException("Script de extração ausente: " + recurso);
            StringBuilder sb = new StringBuilder();
            boolean cabecalho = true;
            for (String linha : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n", -1)) {
                if (cabecalho && (linha.isBlank() || linha.strip().startsWith("//"))) continue;
                cabecalho = false;
                sb.append(linha).append('\n');
            }
            return sb.toString().strip();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
