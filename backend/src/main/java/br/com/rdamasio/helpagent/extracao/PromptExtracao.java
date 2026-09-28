package br.com.rdamasio.helpagent.extracao;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import br.com.rdamasio.helpagent.orcamento.ModoAquisicao;

/**
 * Monta o prompt a partir de {@code prompts/extracao.txt}. No HTML v3.5 havia dois prompts quase
 * idênticos (OPEX/CAPEX) copiados à mão; aqui é um só, e só os trechos que diferem são trocados.
 */
@Component
public class PromptExtracao {

    private final String modelo;

    public PromptExtracao() {
        try (InputStream in = new ClassPathResource("prompts/extracao.txt").getInputStream()) {
            this.modelo = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("prompts/extracao.txt ausente", e);
        }
    }

    public String montar(ModoAquisicao modo, int totalChamados, int totalOrcamentos) {
        Map<String, String> v = new LinkedHashMap<>();
        v.put("total_orcs", String.valueOf(totalOrcamentos));

        if (modo == ModoAquisicao.CAPEX) {
            v.put("abertura", "Analise o(s) " + totalOrcamentos + " orçamento(s) de fornecedor(es) anexado(s). "
                    + "Esta é uma aquisição CAPEX (investimento sem chamado de referência).");
            v.put("campo_chamado", "");
            v.put("campo_loja_num", "");
            v.put("campo_loja_nome", "");
            v.put("campo_titulo", "título curto e técnico para o orçamento (baseado no conjunto dos orçamentos)");
            v.put("campo_observacao", "frase curta e genérica em uma linha começando com 'CAPEX. ', no formato "
                    + "'natureza da despesa + objeto'. Exemplos válidos: 'CAPEX. Aquisição de servidor para datacenter.', "
                    + "'CAPEX. Aquisição de switches gerenciáveis para rede corporativa.', 'CAPEX. Renovação de parque de notebooks.'. "
                    + "NUNCA escreva parágrafos longos, NUNCA mencione chamado (não existe), NUNCA justifique. "
                    + "Máximo 1 frase após 'CAPEX.'.");
            v.put("regra_extra", "\n- Como não há chamado, deixe chamado_num, loja_num e loja_nome como string vazia "
                    + "— o usuário preencherá loja manualmente.");
        } else {
            // OPEX e Requisição/Chamado: mesmo fluxo com chamado; o OPEX só ganha o rótulo no começo da observação.
            // Com prefixo vazio o texto é idêntico ao de antes dos três tipos — a precisão medida continua valendo.
            String p = modo == ModoAquisicao.OPEX ? "OPEX. " : "";
            boolean multi = totalChamados > 1;
            v.put("abertura", "Analise os documentos: os " + totalChamados + " PRIMEIRO(S) documento(s) são chamado(s) "
                    + "interno(s); os " + totalOrcamentos + " seguintes são orçamento(s) de fornecedor(es)."
                    + (multi ? " IMPORTANTE: há MAIS DE UM chamado. Todos referem-se à mesma compra (mesmo CNPJ/fornecedor) "
                            + "e devem ser considerados juntos." : ""));
            v.put("campo_chamado", multi
                    ? "números dos chamados separados por vírgula (ex: '1021069, 1021070')"
                    : "número do chamado (ex: 1021069)");
            v.put("campo_loja_num", "número da loja como aparece (ex: 812, 23, 023)");
            v.put("campo_loja_nome", "nome reduzido da loja (ex: TDPI SUL ADM, DAMASIO PE)");
            v.put("campo_titulo", "título curto e técnico para o orçamento");
            v.put("campo_observacao", "frase curta e genérica em uma linha começando com '" + p
                    + (multi ? "# [nums_chamados separados por vírgula]" : "# [num_chamado]")
                    + ".', no formato 'natureza da despesa + objeto + identificação rápida'. Exemplos válidos: "
                    + "'" + p + "# 1021069. Manutenção de notebook do colaborador Antonio Felipe.', '" + p
                    + "# 1021070. Manutenção de nobreak CPL CE.', "
                    + "'" + p + "# 1021071, 1021072. Aquisição de kit de CFTV para nova loja.'. NUNCA escreva parágrafos longos, "
                    + "NUNCA descreva sintomas ou impacto operacional, NUNCA repita o que está no chamado. "
                    + "Máximo 1 frase após o(s) número(s) do(s) chamado(s).");
            v.put("regra_extra", "");
        }

        String prompt = modelo;
        for (var e : v.entrySet()) {
            prompt = prompt.replace("{{" + e.getKey() + "}}", e.getValue());
        }
        return prompt;
    }
}
