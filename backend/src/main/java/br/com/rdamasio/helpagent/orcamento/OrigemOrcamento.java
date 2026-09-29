package br.com.rdamasio.helpagent.orcamento;

/**
 * De onde vieram os itens do impresso. O valor vai para o banco (coluna {@code origem}) e para o backup:
 * não renomear as constantes.
 */
public enum OrigemOrcamento {
    /** Fluxo de sempre: orçamentos de fornecedor anexados (lidos pela IA ou digitados). */
    DOCUMENTOS,
    /** Montado pela cotação em lojas online, com os prints das 3 opções de cada item anexados. */
    COTACAO
}
