package br.com.rdamasio.helpagent.common;

/**
 * CNPJ: validação pelos dígitos verificadores e formatação padrão (00.000.000/0000-00).
 * O CNPJ da loja sai no impresso oficial — um dígito trocado no cadastro vira documento errado.
 */
public final class Cnpj {

    private static final int[] PESOS_1 = {5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2};
    private static final int[] PESOS_2 = {6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2};

    private Cnpj() {
    }

    public static String somenteDigitos(String cnpj) {
        return cnpj == null ? "" : cnpj.replaceAll("\\D", "");
    }

    public static boolean valido(String cnpj) {
        String d = somenteDigitos(cnpj);
        if (d.length() != 14 || d.chars().distinct().count() == 1) return false; // 00000000000000 etc.
        return digito(d, PESOS_1) == d.charAt(12) - '0' && digito(d, PESOS_2) == d.charAt(13) - '0';
    }

    public static String formatar(String cnpj) {
        String d = somenteDigitos(cnpj);
        if (d.length() != 14) return cnpj;
        return d.substring(0, 2) + "." + d.substring(2, 5) + "." + d.substring(5, 8) + "/" + d.substring(8, 12) + "-"
                + d.substring(12);
    }

    private static int digito(String d, int[] pesos) {
        int soma = 0;
        for (int i = 0; i < pesos.length; i++) soma += (d.charAt(i) - '0') * pesos[i];
        int resto = soma % 11;
        return resto < 2 ? 0 : 11 - resto;
    }
}
