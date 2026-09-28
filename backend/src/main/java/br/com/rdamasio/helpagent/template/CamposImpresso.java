package br.com.rdamasio.helpagent.template;

import java.util.List;

/**
 * Nomes dos campos AcroForm dos impressos. Os quatro templates atuais usam os mesmos nomes
 * (herança de terem saído do mesmo arquivo-base), por isso o mapa é único.
 *
 * <p>Se um template novo vier com nomes diferentes, este mapa precisa virar dado por template —
 * o {@code PdfOrcamentoServiceTest} falha se algum nome daqui não existir num dos PDFs.
 */
public final class CamposImpresso {

    private CamposImpresso() {
    }

    public static final String TITULO = "Caixa de texto 1_2";
    public static final String DEPARTAMENTO = "Caixa de texto 1";
    public static final String EMPRESA = "Caixa de texto 1_3";
    public static final String CNPJ = "Caixa de texto 1_4";
    public static final String REQUERENTE = "Caixa de texto 1_5";
    public static final String EMITIDO = "Caixa de texto 2";
    /** "Válido até:" — existia no impresso e a v3.5 nunca preenchia; preenchido desde a V3 do banco. */
    public static final String VALIDADE = "Caixa de texto 1_6";
    public static final String SUBTOTAL = "Caixa de texto 1_8";
    public static final String FRETE = "Caixa de texto 1_9";
    public static final String ACRESCIMOS = "Caixa de texto 1_10";
    public static final String TOTAL = "Caixa de texto 1_11";
    public static final String OBSERVACOES = "Caixa de texto 1_7";
    public static final String VARIACAO = "Caixa de texto 1_12";
    public static final String DIA = "Caixa de texto 2_3";
    public static final String MES = "Caixa de texto 2_2";
    public static final String ANO = "Caixa de texto 2_4";
    public static final String REQUERENTE_ASSINATURA = "Caixa de texto 3";
    public static final String GESTOR = "Caixa de texto 3_2";

    /** Uma linha de item do impresso: ordem, produto, descrição, qtd, unitário, total. */
    public record LinhaItem(String ordem, String produto, String descricao, String qtd, String unitario, String total) {
    }

    public static final List<LinhaItem> LINHAS_ITENS = List.of(
            new LinhaItem("Caixa de texto 4_38", "Caixa de texto 4_2", "Caixa de texto 4", "Caixa de texto 4_61", "Caixa de texto 4_14", "Caixa de texto 4_26"),
            new LinhaItem("Caixa de texto 4_39", "Caixa de texto 4_3", "Caixa de texto 4_50", "Caixa de texto 4_62", "Caixa de texto 4_15", "Caixa de texto 4_27"),
            new LinhaItem("Caixa de texto 4_40", "Caixa de texto 4_4", "Caixa de texto 4_51", "Caixa de texto 4_63", "Caixa de texto 4_16", "Caixa de texto 4_28"),
            new LinhaItem("Caixa de texto 4_41", "Caixa de texto 4_5", "Caixa de texto 4_52", "Caixa de texto 4_64", "Caixa de texto 4_17", "Caixa de texto 4_29"),
            new LinhaItem("Caixa de texto 4_42", "Caixa de texto 4_6", "Caixa de texto 4_53", "Caixa de texto 4_65", "Caixa de texto 4_18", "Caixa de texto 4_30"),
            new LinhaItem("Caixa de texto 4_43", "Caixa de texto 4_7", "Caixa de texto 4_54", "Caixa de texto 4_66", "Caixa de texto 4_19", "Caixa de texto 4_31"),
            new LinhaItem("Caixa de texto 4_44", "Caixa de texto 4_8", "Caixa de texto 4_55", "Caixa de texto 4_67", "Caixa de texto 4_20", "Caixa de texto 4_32"),
            new LinhaItem("Caixa de texto 4_45", "Caixa de texto 4_9", "Caixa de texto 4_56", "Caixa de texto 4_68", "Caixa de texto 4_21", "Caixa de texto 4_33"),
            new LinhaItem("Caixa de texto 4_46", "Caixa de texto 4_10", "Caixa de texto 4_57", "Caixa de texto 4_69", "Caixa de texto 4_22", "Caixa de texto 4_34"),
            new LinhaItem("Caixa de texto 4_47", "Caixa de texto 4_11", "Caixa de texto 4_58", "Caixa de texto 4_70", "Caixa de texto 4_23", "Caixa de texto 4_35"));
}
