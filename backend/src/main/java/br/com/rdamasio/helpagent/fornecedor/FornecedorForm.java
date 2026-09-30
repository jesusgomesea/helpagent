package br.com.rdamasio.helpagent.fornecedor;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Cadastro/edição de fornecedor pela tela.
 *
 * @param nome     nome padronizado (o que aparece no histórico)
 * @param cnpj     opcional; quando existe, é a forma mais segura de reconhecer
 * @param apelidos outros nomes com que ele aparece nos documentos
 */
public record FornecedorForm(
        @NotBlank @Size(max = 120) String nome,
        String cnpj,
        List<@Size(max = 200) String> apelidos) {
}
