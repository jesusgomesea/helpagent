package br.com.rdamasio.helpagent.cotacao;

import java.util.ArrayList;
import java.util.List;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Kabum (kabum.com.br) — varejo de TI, nível 1. Lê o JSON do Next.js ({@code extrair-kabum.js}); 60 por página.
 * Preço usado: à vista no PIX. Distingue o que a KaBuM! vende do que é de lojista parceiro (marketplace).
 */
@Component
@Order(10) // ordem na tela e na planilha: por grupo (varejo de TI, marketplaces, fabricantes)
public class FonteKabum extends FonteComScript {

    public FonteKabum() {
        super("kabum", "Kabum", Grupo.VAREJO_TI, "script#__NEXT_DATA__");
    }

    @Override
    public List<String> urls(String termo, int paginas) {
        List<String> urls = new ArrayList<>();
        String base = "https://www.kabum.com.br/busca/" + slug(termo);
        for (int p = 1; p <= paginas; p++) urls.add(p == 1 ? base : base + "?page_number=" + p);
        return urls;
    }
}
