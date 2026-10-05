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
        return montar(modo, totalChamados, totalOrcamentos, false);
    }

    /**
     * @param detalhar {@code false} (padrão): 1 linha por orçamento, resumindo o que ele inclui — o texto é o mesmo
     *                 de antes da opção, então a precisão medida continua valendo. {@code true}: cada item
     *                 discriminado no orçamento vira a sua linha, com qtd e unitário do fornecedor (desde 05/10/2026).
     */
    public String montar(ModoAquisicao modo, int totalChamados, int totalOrcamentos, boolean detalhar) {
        Map<String, String> v = new LinkedHashMap<>();
        if (detalhar) {
            v.put("formato_itens", "FORMATO CRÍTICO DOS ITENS: cada item discriminado nos orçamentos anexados (cada linha da "
                    + "tabela de produtos/serviços do fornecedor) vira UM item próprio no array \"itens\", na mesma ordem "
                    + "do documento, com a SUA quantidade, o SEU valor unitário e o SEU valor total, como o fornecedor "
                    + "escreveu. NÃO agrupe, NÃO some e NÃO resuma itens diferentes numa linha só. Serviços cobrados à "
                    + "parte (mão de obra, instalação, configuração, frete cobrado como item) também são itens. Linhas "
                    + "de subtotal, total, impostos já inclusos e formas de pagamento NÃO são itens. Se um orçamento não "
                    + "discriminar itens (só traz um valor global), ele vira 1 item com esse valor.\n"
                    + "Portanto: {{total_orcs}} orçamento(s) anexado(s) → pelo menos {{total_orcs}} item(ns) no array "
                    + "\"itens\", um por linha discriminada.");
            v.put("campo_produto", "o que é este item, curto e genérico (ex: 'Central de alarme', 'Sensor "
                    + "infravermelho', 'Mão de obra')");
            v.put("campo_descricao", "o resto da linha do orçamento, SEM repetir o produto: marca, modelo, medida, "
                    + "código ou o que o serviço inclui (ex: 'Intelbras AMT 2018 E', 'IVP 3000 MW', 'instalação e configuração'); string vazia se "
                    + "não houver");
            v.put("regra_qtd", "- qtd = a quantidade escrita na linha do item; valor_unit = o unitário dessa linha. Se a "
                    + "linha só tiver o total, use qtd \"1\" e valor_unit = valor_total. Desconto geral do orçamento: não "
                    + "crie item negativo; mantenha os itens como escritos e ponha o valor final em \"total\".");
        } else {
            v.put("formato_itens", "FORMATO CRÍTICO DOS ITENS: cada orçamento anexado deve preencher um item consolidado no "
                    + "JSON, Como uma lista de componentes. Em que cada item tem o seu valor_uni e valor_total calculado a "
                    + "partir da quantidade, se não conseguir preencher a quantidade, siga para o preview após validar o "
                    + "restante dos itens.\nPortanto: {{total_orcs}} orçamento(s) anexado(s) → {{total_orcs}} item(ns) no "
                    + "array \"itens\".");
            v.put("campo_produto", "nome geral / categoria do que foi orçado (ex: 'Central de Alarme', 'Notebook Lenovo', "
                    + "'Kit CFTV')");
            v.put("campo_descricao", "resumo do que está incluso no orçamento (ex: 'Central + 8 sensores + teclado + módulo "
                    + "GPRS + sirene', 'Notebook i5 8GB 256SSD com mouse e mochila')");
            v.put("regra_qtd", "- qtd variável \"quase sempre não estará evidente no orçamento\" Com isto, se não "
                    + "identificada, seguir para o proximo até o preview.");
        }
        // total_orcs depois do formato: o texto do formato também usa {{total_orcs}}
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
