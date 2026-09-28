package br.com.rdamasio.helpagent.cotacao;

import java.util.ArrayList;
import java.util.List;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Amazon (amazon.com.br) — marketplace, nível 2. Cartões s-search-result ({@code extrair-amazon.js}).
 * Preço usado: o do cartão (a listagem não mostra desconto de PIX). Prime conta como entrega rápida.
 */
@Component
@Order(40) // ordem na tela e na planilha: por grupo (varejo de TI, marketplaces, fabricantes)
public class FonteAmazon extends FonteComScript {

    public FonteAmazon() {
        super("amazon", "Amazon", Grupo.MARKETPLACE, "div[data-component-type='s-search-result']");
    }

    @Override
    public List<String> urls(String termo, int paginas) {
        List<String> urls = new ArrayList<>();
        String base = "https://www.amazon.com.br/s?k=" + query(termo);
        for (int p = 1; p <= paginas; p++) urls.add(p == 1 ? base : base + "&page=" + p);
        return urls;
    }
}
