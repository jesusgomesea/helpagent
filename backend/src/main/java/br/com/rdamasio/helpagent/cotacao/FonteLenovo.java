package br.com.rdamasio.helpagent.cotacao;

import java.util.List;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Lenovo (lenovo.com/br) — loja do fabricante, nível 3. Cartões montados por JavaScript ({@code extrair-lenovo.js}).
 * Em vez de abrir páginas separadas, pede mais itens na mesma ({@code rows}): 20 por "página".
 */
@Component
@Order(70) // ordem na tela e na planilha: por grupo (varejo de TI, marketplaces, fabricantes)
public class FonteLenovo extends FonteComScript {

    public FonteLenovo() {
        super("lenovo", "Lenovo", Grupo.FABRICANTE, ".product_item[data-product-code]");
    }

    @Override
    public List<String> urls(String termo, int paginas) {
        return List.of("https://www.lenovo.com/br/pt/search?text=" + query(termo) + "&rows=" + (20 * Math.max(1, paginas)));
    }
}
