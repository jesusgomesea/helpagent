package br.com.rdamasio.helpagent.extracao;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import br.com.rdamasio.helpagent.common.Dinheiro;

/** Conferências sobre o que a IA devolveu — viram avisos na tela de revisão, não bloqueiam. */
public final class RegrasExtracao {

    private static final BigDecimal TOLERANCIA = new BigDecimal("0.05");

    private RegrasExtracao() {
    }

    public static List<String> avisos(DadosExtraidos dados, int maxItens) {
        List<String> avisos = new ArrayList<>();

        // A2 — o impresso tem maxItens linhas; o excedente não entra no formulário nem no total.
        int descartados = Math.max(0, dados.itens().size() - maxItens);
        if (descartados > 0) {
            avisos.add("A IA identificou " + dados.itens().size() + " itens e o impresso aceita apenas " + maxItens
                    + ". " + descartados + " item(ns) não entrou(ram) no formulário nem no total. "
                    + "Consolide manualmente ou gere dois orçamentos.");
        }

        // C2 — o total lido pela IA serve de conferente da soma das linhas.
        BigDecimal totalIa = Dinheiro.parse(dados.total());
        BigDecimal soma = somaItens(dados.itens().subList(0, Math.min(maxItens, dados.itens().size())));
        if (totalIa.signum() > 0 && totalIa.subtract(soma).abs().compareTo(TOLERANCIA) > 0) {
            avisos.add("Divergência de total: a IA leu " + Dinheiro.formatar(totalIa) + " e a soma dos itens dá "
                    + Dinheiro.formatar(soma) + " (diferença de " + Dinheiro.formatar(totalIa.subtract(soma).abs())
                    + "). Confira os valores unitários antes de gerar.");
        }
        return avisos;
    }

    /** Mesma conta da tela: qtd × unitário (o valor_total da IA só vale quando não há unitário). */
    static BigDecimal somaItens(List<DadosExtraidos.Item> itens) {
        BigDecimal soma = BigDecimal.ZERO;
        for (DadosExtraidos.Item i : itens) {
            BigDecimal unit = Dinheiro.parse(i.valorUnit());
            if (unit.signum() > 0) {
                BigDecimal qtd = Dinheiro.parse(i.qtd() == null || i.qtd().isBlank() ? "1" : i.qtd());
                soma = soma.add(qtd.multiply(unit));
            } else {
                soma = soma.add(Dinheiro.parse(i.valorTotal()));
            }
        }
        return Dinheiro.arredondar(soma);
    }
}
