package br.com.rdamasio.helpagent.pdf;

import java.time.LocalDate;
import java.util.List;

import br.com.rdamasio.helpagent.common.Documento;
import br.com.rdamasio.helpagent.template.TemplateCodigo;

/** Tudo que vai para o impresso, já formatado como texto. Campos nulos ficam em branco. */
public record DadosImpresso(
        TemplateCodigo template,
        String titulo,
        String departamento,
        String empresa,
        String cnpj,
        String requerente,
        String gestor,
        LocalDate dataEmissao,
        /** "Válido até"; null deixa o campo em branco. */
        LocalDate validade,
        List<Linha> linhas,
        String subtotal,
        String frete,
        String acrescimos,
        String total,
        String observacoes,
        String variacao,
        List<Anexo> anexos) {

    public record Linha(String produto, String descricao, String quantidade, String unitario, String total) {
    }

    /** Documento anexado depois do formulário, com o rótulo carimbado no topo da primeira página. */
    public record Anexo(Documento documento, String rotulo) {
    }
}
