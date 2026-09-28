package br.com.rdamasio.helpagent.orcamento;

import java.util.ArrayList;
import java.util.List;

import br.com.rdamasio.helpagent.common.Dinheiro;

/**
 * A1 — o bug mais grave da v3.0: o total somava toda linha com valor, mas o impresso só mostrava
 * linha com produto. Nunca gerar um impresso cujo total não fecha com as linhas visíveis.
 */
public final class ValidadorOrcamento {

    private ValidadorOrcamento() {
    }

    public static List<String> problemas(GerarOrcamentoRequest r, int maxItens) {
        List<String> problemas = new ArrayList<>();
        int impressas = 0;
        for (int i = 0; i < r.itens().size(); i++) {
            GerarOrcamentoRequest.Item item = r.itens().get(i);
            String linha = String.format("%02d", i + 1);
            var total = CalculoOrcamento.totalItem(item);
            if (!CalculoOrcamento.temProduto(item)) {
                if (total.signum() > 0) {
                    problemas.add("o item da linha " + linha + " tem valor (" + Dinheiro.formatar(total)
                            + ") mas está sem nome de produto");
                }
                continue;
            }
            impressas++;
            if (total.signum() <= 0) problemas.add("o item \"" + item.produto().trim() + "\" está sem valor");
        }
        if (impressas == 0) problemas.add("nenhum item preenchido");
        if (impressas > maxItens) {
            problemas.add("o impresso aceita até " + maxItens + " itens e há " + impressas
                    + " — consolide ou gere dois orçamentos");
        }
        return problemas;
    }
}
