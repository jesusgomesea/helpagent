package br.com.rdamasio.helpagent.cotacao;

import java.util.List;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Dell (dell.com/pt-br) — loja do fabricante, nível 3. JSON por cartão em data-product-detail ({@code extrair-dell.js}).
 * Catálogo pequeno: uma página basta, por isso ignora {@code paginas}. Faz sentido para notebooks, desktops,
 * monitores e acessórios Dell — para outros itens a Dell simplesmente não devolve nada.
 */
@Component
@Order(60) // ordem na tela e na planilha: por grupo (varejo de TI, marketplaces, fabricantes)
public class FonteDell extends FonteComScript {

    public FonteDell() {
        super("dell", "Dell", Grupo.FABRICANTE, "[data-product-detail]");
    }

    @Override
    public List<String> urls(String termo, int paginas) {
        // a Dell usa o termo no caminho; espaço como %20 (o "+" da query string viraria parte do termo)
        return List.of("https://www.dell.com/pt-br/search/" + query(termo).replace("+", "%20"));
    }
}
