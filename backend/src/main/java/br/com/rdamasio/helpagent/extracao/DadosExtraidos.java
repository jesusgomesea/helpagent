package br.com.rdamasio.helpagent.extracao;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * O JSON que o prompt pede à IA. Valores chegam como texto no formato brasileiro ("1.500,00").
 *
 * <p>Mudou um campo aqui? Mudar também o prompt ({@code prompts/extracao.txt}), o esquema de resposta
 * ({@code GeminiClient.ESQUEMA_RESPOSTA}), o tipo do frontend ({@code core/modelos.ts}) e a ferramenta de
 * avaliação ({@code tools/avaliar_extracao.py}).
 *
 * @param validadeAte  validade explícita no documento ("válida até 31/07/2026"), em dd/mm/aaaa
 * @param validadeDias validade relativa ("validade: 7 dias"); a data é calculada no sistema, não pela IA
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DadosExtraidos(
        @JsonProperty("chamado_num") String chamadoNum,
        @JsonProperty("loja_num") String lojaNum,
        @JsonProperty("loja_nome") String lojaNome,
        String titulo,
        List<Item> itens,
        String total,
        String observacao,
        @JsonProperty("validade_ate") String validadeAte,
        @JsonProperty("validade_dias") String validadeDias) {

    public DadosExtraidos {
        itens = itens == null ? List.of() : List.copyOf(itens);
    }

    /**
     * @param fonte onde a IA leu o valor (página e trecho). Só vai para o log — serve para auditar
     *              leituras erradas depois, sem poluir a tela de revisão
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Item(
            String produto,
            String descricao,
            String qtd,
            @JsonProperty("valor_unit") String valorUnit,
            @JsonProperty("valor_total") String valorTotal,
            String fonte) {
    }
}
