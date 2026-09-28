package br.com.rdamasio.helpagent.orcamento;

/**
 * Tipo de requisição. Define se o chamado é obrigatório e como a observação do impresso começa.
 *
 * <ul>
 *   <li>{@link #REQUISICAO}: o fluxo normal, com chamado — observação "# 1021069. Manutenção de…". Era o que o
 *       sistema chamava de OPEX até 28/09/2026; os orçamentos antigos foram reclassificados para cá
 *       (migration V4).</li>
 *   <li>{@link #OPEX}: despesa operacional, com chamado — observação marcada "OPEX. # 1021069. …".</li>
 *   <li>{@link #CAPEX}: investimento, sem chamado; a loja é informada à mão — observação "CAPEX. …".</li>
 * </ul>
 *
 * O nome exibido ao usuário fica em {@code frontend/src/app/core/modelos.ts} (ROTULO_MODO), para poder
 * mudar ("Requisição / Chamado" é provisório) sem mexer em banco nem em backup. O nome da constante é o
 * que vai para o banco: não renomear.
 */
public enum ModoAquisicao {
    OPEX(true),
    CAPEX(false),
    REQUISICAO(true);

    private final boolean exigeChamado;

    ModoAquisicao(boolean exigeChamado) {
        this.exigeChamado = exigeChamado;
    }

    /** Se o chamado é obrigatório na leitura e vai para a IA junto com os orçamentos. */
    public boolean exigeChamado() {
        return exigeChamado;
    }
}
