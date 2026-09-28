package br.com.rdamasio.helpagent.cotacao;

import java.util.List;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Terabyte (terabyteshop.com.br) — varejo de TI, nível 1. Cartões com atributos data-tss-* ({@code extrair-terabyte.js}).
 * Preço usado: à vista no Pix. A busca devolve a lista inteira numa página só, por isso ignora {@code paginas}.
 */
@Component
@Order(30) // ordem na tela e na planilha: por grupo (varejo de TI, marketplaces, fabricantes)
public class FonteTerabyte extends FonteComScript {

    public FonteTerabyte() {
        super("terabyte", "Terabyte", Grupo.VAREJO_TI, ".product-item");
    }

    @Override
    public List<String> urls(String termo, int paginas) {
        return List.of("https://www.terabyteshop.com.br/busca?str=" + query(termo));
    }
}
