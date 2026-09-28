package br.com.rdamasio.helpagent.cotacao;

import java.util.ArrayList;
import java.util.List;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Pichau (pichau.com.br) — varejo de TI, nível 1. Lê os dados do Next.js ({@code extrair-pichau.js}); ~36 por página.
 * Preço usado: à vista no PIX. A listagem não traz avaliação; quem vende é a própria Pichau.
 */
@Component
@Order(20) // ordem na tela e na planilha: por grupo (varejo de TI, marketplaces, fabricantes)
public class FontePichau extends FonteComScript {

    public FontePichau() {
        // os dados vêm em scripts inline: basta o HTML carregado (qualquer link já indica isso)
        super("pichau", "Pichau", Grupo.VAREJO_TI, "a[href]");
    }

    @Override
    public List<String> urls(String termo, int paginas) {
        List<String> urls = new ArrayList<>();
        // espaço como %20, como o próprio site gera
        String base = "https://www.pichau.com.br/search?q=" + query(termo).replace("+", "%20");
        for (int p = 1; p <= paginas; p++) urls.add(p == 1 ? base : base + "&page=" + p);
        return urls;
    }
}
