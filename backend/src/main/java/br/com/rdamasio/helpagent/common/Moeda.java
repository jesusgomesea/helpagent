package br.com.rdamasio.helpagent.common;

/**
 * Moeda em que o impresso sai. O padrão é o real; orçamento de fornecedor em dólar ou euro sai na moeda original,
 * sem conversão (decisão de 15/10/2026): o valor do impresso é o mesmo do documento anexado.
 *
 * <p>O símbolo vai nos campos do PDF (o template não tem "R$" fixo). O "€" existe na fonte WinAnsi do formulário.
 * Moeda nova: incluir aqui, no {@code core/dinheiro.ts} do frontend e conferir se o símbolo existe no WinAnsi.
 */
public enum Moeda {
    BRL("R$"),
    USD("US$"),
    EUR("€");

    private final String simbolo;

    Moeda(String simbolo) {
        this.simbolo = simbolo;
    }

    public String simbolo() {
        return simbolo;
    }

    /** Pedidos e backups de antes da moeda existir não a informam: são em real. */
    public static Moeda ouReal(Moeda m) {
        return m == null ? BRL : m;
    }
}
