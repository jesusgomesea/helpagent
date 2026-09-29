package br.com.rdamasio.helpagent.loja;

import br.com.rdamasio.helpagent.template.TemplateCodigo;

/**
 * Loja como o frontend a vê; {@code templateRotulo} é o nome amigável do impresso (ex.: "TD MOTOPEÇAS").
 */
public record LojaDto(int numero, String nome, String cnpj, String empresa, TemplateCodigo template, String templateRotulo,
        boolean ativa, String razaoSocial, String inscricaoEstadual, String cidade, String uf) {

    public static LojaDto de(Loja l) {
        return new LojaDto(l.getNumero(), l.getNome(), l.getCnpj(), l.getEmpresa(), l.getTemplate(), l.getTemplate().rotulo(),
                l.isAtiva(), l.getRazaoSocial(), l.getInscricaoEstadual(), l.getCidade(), l.getUf());
    }
}
