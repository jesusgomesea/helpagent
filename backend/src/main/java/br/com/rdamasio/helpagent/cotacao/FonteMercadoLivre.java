package br.com.rdamasio.helpagent.cotacao;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Mercado Livre (lista.mercadolivre.com.br) — marketplace, nível 2. Extração em
 * {@code resources/cotacao/extrair-mercadolivre.js}, copiada sem alteração do piloto de Suprimentos.
 * Os links patrocinados saem como rastreamento e são trocados depois por {@link ResolvedorPatrocinados}.
 */
@Component
@Order(50) // ordem na tela e na planilha: por grupo (varejo de TI, marketplaces, fabricantes)
public class FonteMercadoLivre extends FonteComScript {

    /** O ML pagina de 50 em 50: a 2ª página é /termo_Desde_51. */
    private static final int POR_PAGINA = 50;

    public FonteMercadoLivre() {
        super("mercadolivre", "Mercado Livre", Grupo.MARKETPLACE, "li.ui-search-layout__item");
    }

    @Override
    public List<String> urls(String termo, int paginas) {
        List<String> urls = new ArrayList<>();
        String base = "https://lista.mercadolivre.com.br/" + slugPiloto(termo);
        for (int p = 0; p < paginas; p++) urls.add(p == 0 ? base : base + "_Desde_" + (p * POR_PAGINA + 1));
        return urls;
    }

    /**
     * Slug do piloto: minúsculas, sem pontuação, espaços viram hífen ("SSD 256GB" → "ssd-256gb").
     * Diferente do {@link FonteComScript#slug}, mantém letras acentuadas — é o que o ML aceita e o que foi validado.
     */
    static String slugPiloto(String termo) {
        String s = termo.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}_\\s-]", "");
        return s.strip().replaceAll("[\\s_]+", "-");
    }
}
