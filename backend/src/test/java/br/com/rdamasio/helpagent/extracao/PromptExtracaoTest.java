package br.com.rdamasio.helpagent.extracao;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import br.com.rdamasio.helpagent.orcamento.ModoAquisicao;

/** Os três tipos de requisição só diferem no começo da observação; o resto do prompt é o mesmo. */
class PromptExtracaoTest {

    private final PromptExtracao prompt = new PromptExtracao();

    @Test
    void requisicaoMantemAObservacaoDeSempre() {
        String p = prompt.montar(ModoAquisicao.REQUISICAO, 1, 1);
        assertThat(p).contains("começando com '# [num_chamado].'").contains("'# 1021069. Manutenção de notebook");
        assertThat(p).doesNotContain("OPEX.").doesNotContain("CAPEX.");
    }

    @Test
    void opexMarcaAObservacao() {
        String p = prompt.montar(ModoAquisicao.OPEX, 2, 1);
        assertThat(p).contains("começando com 'OPEX. # [nums_chamados separados por vírgula].'")
                .contains("'OPEX. # 1021069. Manutenção de notebook")
                .contains("há MAIS DE UM chamado");
    }

    @Test
    void opexERequisicaoSoDiferemNoRotulo() {
        String req = prompt.montar(ModoAquisicao.REQUISICAO, 1, 2);
        String opex = prompt.montar(ModoAquisicao.OPEX, 1, 2);
        assertThat(opex.replace("OPEX. ", "")).isEqualTo(req);
    }

    @Test
    void capexNaoFalaDeChamado() {
        assertThat(prompt.montar(ModoAquisicao.CAPEX, 0, 1)).contains("começando com 'CAPEX. '").contains("aquisição CAPEX");
    }
}
