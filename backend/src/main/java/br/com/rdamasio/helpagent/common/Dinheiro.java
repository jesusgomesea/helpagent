package br.com.rdamasio.helpagent.common;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/**
 * Valores no formato brasileiro (vírgula decimal), em real por padrão ou na {@link Moeda} do orçamento. Porta de {@code parseBRL}/{@code fmtBRL} do HTML v3.5,
 * mas com {@link BigDecimal}: o original somava em {@code float} e podia errar centavos.
 */
public final class Dinheiro {

    private static final Locale PT_BR = Locale.of("pt", "BR");

    private Dinheiro() {
    }

    /**
     * "R$ 1.234,56", "US$ 1.234,56", "€ 1.234,56", "1234,56", "1.234" → BigDecimal. Vazio ou ilegível vira zero,
     * como no original. O símbolo é descartado: a moeda vem do orçamento, não do texto.
     */
    public static BigDecimal parse(String texto) {
        if (texto == null || texto.isBlank()) return BigDecimal.ZERO;
        String limpo = texto.replaceAll("[^0-9,.\\-]", "").replace(".", "").replace(',', '.');
        try {
            return new BigDecimal(limpo).setScale(2, RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    /** BigDecimal → "R$ 1.234,56". */
    public static String formatar(BigDecimal valor) {
        return formatar(valor, Moeda.BRL);
    }

    /** BigDecimal → "US$ 1.234,56" (símbolo da moeda, número sempre no formato brasileiro, como o resto do impresso). */
    public static String formatar(BigDecimal valor, Moeda moeda) {
        DecimalFormat fmt = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(PT_BR));
        return Moeda.ouReal(moeda).simbolo() + " " + fmt.format(valor.setScale(2, RoundingMode.HALF_UP));
    }

    public static BigDecimal arredondar(BigDecimal valor) {
        return valor.setScale(2, RoundingMode.HALF_UP);
    }

    public static BigDecimal ouZero(BigDecimal valor) {
        return valor == null ? BigDecimal.ZERO : valor;
    }
}
