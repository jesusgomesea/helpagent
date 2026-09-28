package br.com.rdamasio.helpagent.orcamento;

import java.math.BigDecimal;
import java.util.List;

import br.com.rdamasio.helpagent.common.Dinheiro;

/** Contas do orçamento, em BigDecimal. O servidor recalcula tudo — não confia no total da tela. */
public final class CalculoOrcamento {

    private CalculoOrcamento() {
    }

    public static BigDecimal quantidade(GerarOrcamentoRequest.Item item) {
        return item.quantidade() == null || item.quantidade().signum() == 0 ? BigDecimal.ONE : item.quantidade();
    }

    public static BigDecimal totalItem(GerarOrcamentoRequest.Item item) {
        return Dinheiro.arredondar(quantidade(item).multiply(Dinheiro.ouZero(item.valorUnitario())));
    }

    /** Soma só das linhas impressas (com produto), sem frete e acréscimos. */
    public static BigDecimal somaItens(List<GerarOrcamentoRequest.Item> itens) {
        return itens.stream().filter(CalculoOrcamento::temProduto)
                .map(CalculoOrcamento::totalItem)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public static BigDecimal totalGeral(GerarOrcamentoRequest r) {
        return Dinheiro.arredondar(somaItens(r.itens())
                .add(Dinheiro.ouZero(r.frete()))
                .add(Dinheiro.ouZero(r.acrescimos())));
    }

    public static boolean temProduto(GerarOrcamentoRequest.Item item) {
        return item.produto() != null && !item.produto().isBlank();
    }
}
