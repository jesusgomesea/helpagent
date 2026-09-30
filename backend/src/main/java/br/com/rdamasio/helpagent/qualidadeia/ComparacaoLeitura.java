package br.com.rdamasio.helpagent.qualidadeia;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import br.com.rdamasio.helpagent.common.Dinheiro;
import br.com.rdamasio.helpagent.extracao.DadosExtraidos;
import br.com.rdamasio.helpagent.fornecedor.ReconhecimentoFornecedor;
import br.com.rdamasio.helpagent.orcamento.CalculoOrcamento;
import br.com.rdamasio.helpagent.orcamento.GerarOrcamentoRequest;
import br.com.rdamasio.helpagent.orcamento.ModoAquisicao;

/**
 * Compara o que a IA leu com o que o atendente confirmou ao gerar. Regra de "corrigido" por tipo de campo:
 * <ul>
 *   <li><b>texto</b> (título, observação, produto, descrição): diferente depois de ignorar maiúsculas, acentos e
 *       espaços repetidos — qualquer outra edição conta, porque o objetivo é ver o que o atendente mexe;</li>
 *   <li><b>valor</b> (total, unitário) e <b>quantidade</b>: diferença de mais de 1 centavo / 0,001;</li>
 *   <li><b>chamado</b>: os mesmos números (a ordem e a pontuação não importam);</li>
 *   <li><b>loja</b>: o mesmo número ("023" = "23");</li>
 *   <li><b>fornecedor</b>: a mesma "chave" do reconhecimento, ou o lido começando pelo confirmado
 *       ("KABUM COMERCIO..." → "Kabum" é acerto: o cadastro padronizou).</li>
 * </ul>
 * Campo vazio dos dois lados não entra (não há o que comparar). Em CAPEX, chamado e loja não entram: a IA é
 * instruída a não preenchê-los.
 */
public final class ComparacaoLeitura {

    /** Um campo comparado. {@code item} 0 = cabeçalho; 1..n = linha. */
    public record Campo(String campo, int item, String fornecedor, String lido, String confirmado, boolean corrigido) {
    }

    private static final Pattern NUMERO = Pattern.compile("\\d{4,}");

    private ComparacaoLeitura() {
    }

    public static List<Campo> comparar(DadosExtraidos lido, GerarOrcamentoRequest confirmado, BigDecimal totalConfirmado,
            List<String> fornecedoresConfirmados) {
        List<Campo> r = new ArrayList<>();
        texto(r, "titulo", 0, null, lido.titulo(), confirmado.titulo());
        texto(r, "observacao", 0, null, lido.observacao(), confirmado.observacoes());
        if (confirmado.modo() != ModoAquisicao.CAPEX) {
            String lidoCh = String.valueOf(numeros(lido.chamadoNum())), finalCh = String.valueOf(numeros(confirmado.chamadoNum()));
            adicionar(r, "chamado", 0, null, lido.chamadoNum(), confirmado.chamadoNum(),
                    () -> !lidoCh.equals(finalCh));
            adicionar(r, "loja", 0, null, lido.lojaNum(), confirmado.lojaNumero(),
                    () -> !String.valueOf(inteiro(lido.lojaNum())).equals(String.valueOf(inteiro(confirmado.lojaNumero()))));
        }
        valor(r, "total", 0, null, lido.total(), totalConfirmado);

        List<GerarOrcamentoRequest.Item> finais = confirmado.itens().stream().filter(CalculoOrcamento::temProduto).toList();
        int n = Math.min(lido.itens().size(), finais.size());
        for (int i = 0; i < n; i++) {
            DadosExtraidos.Item a = lido.itens().get(i);
            GerarOrcamentoRequest.Item b = finais.get(i);
            String forn = i < fornecedoresConfirmados.size() ? fornecedoresConfirmados.get(i) : null;
            texto(r, "produto", i + 1, forn, a.produto(), b.produto());
            texto(r, "descricao", i + 1, forn, a.descricao(), b.descricao());
            BigDecimal qtdLida = decimal(a.qtd());
            adicionar(r, "quantidade", i + 1, forn, a.qtd(), CalculoOrcamento.quantidade(b).stripTrailingZeros().toPlainString(),
                    () -> qtdLida == null || qtdLida.subtract(CalculoOrcamento.quantidade(b)).abs().compareTo(new BigDecimal("0.001")) > 0);
            valor(r, "valor_unitario", i + 1, forn, a.valorUnit(), Dinheiro.ouZero(b.valorUnitario()));
            fornecedor(r, i + 1, a.fornecedor(), forn);
        }
        if (!lido.itens().isEmpty() || !finais.isEmpty()) {
            adicionar(r, "numero_de_itens", 0, null, String.valueOf(lido.itens().size()), String.valueOf(finais.size()),
                    () -> lido.itens().size() != finais.size());
        }
        return r;
    }

    private static void texto(List<Campo> r, String campo, int item, String forn, String lido, String confirmado) {
        adicionar(r, campo, item, forn, lido, confirmado, () -> !normalizar(lido).equals(normalizar(confirmado)));
    }

    private static void valor(List<Campo> r, String campo, int item, String forn, String lido, BigDecimal confirmado) {
        BigDecimal l = lido == null || lido.isBlank() ? null : Dinheiro.parse(lido);
        String finalTxt = confirmado == null ? null : Dinheiro.formatar(confirmado);
        adicionar(r, campo, item, forn, lido, finalTxt,
                () -> l == null || confirmado == null || l.subtract(confirmado).abs().compareTo(new BigDecimal("0.01")) > 0);
    }

    private static void fornecedor(List<Campo> r, int item, String lido, String confirmado) {
        adicionar(r, "fornecedor", item, confirmado, lido, confirmado, () -> {
            String a = ReconhecimentoFornecedor.chave(lido), b = ReconhecimentoFornecedor.chave(confirmado);
            return !(a.equals(b) || (!b.isEmpty() && a.startsWith(b + " ")));
        });
    }

    private static void adicionar(List<Campo> r, String campo, int item, String forn, String lido, String confirmado,
            java.util.function.BooleanSupplier corrigido) {
        boolean semLido = lido == null || lido.isBlank(), semFinal = confirmado == null || confirmado.isBlank();
        if (semLido && semFinal) return;
        r.add(new Campo(campo, item, forn, cortar(lido), cortar(confirmado), semLido != semFinal || corrigido.getAsBoolean()));
    }

    static String normalizar(String s) {
        if (s == null) return "";
        String t = Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return t.replaceAll("\\s+", " ").strip();
    }

    private static Set<String> numeros(String s) {
        Set<String> r = new TreeSet<>();
        if (s == null) return r;
        Matcher m = NUMERO.matcher(s);
        while (m.find()) r.add(m.group());
        return r;
    }

    private static Integer inteiro(String s) {
        if (s == null) return null;
        String d = s.replaceAll("\\D", "");
        return d.isEmpty() || d.length() > 9 ? null : Integer.valueOf(d);
    }

    private static BigDecimal decimal(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return new BigDecimal(s.strip().replace(".", "").replace(',', '.'));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String cortar(String s) {
        return s == null ? null : s.length() > 1000 ? s.substring(0, 1000) : s;
    }
}
