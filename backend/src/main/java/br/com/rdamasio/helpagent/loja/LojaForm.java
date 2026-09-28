package br.com.rdamasio.helpagent.loja;

import br.com.rdamasio.helpagent.template.TemplateCodigo;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Cadastro/edição de loja pela tela de Lojas. Na edição o {@code numero} vem do caminho e este é ignorado.
 *
 * @param empresa  código da empresa que sai no impresso (ex.: DAMASIO-PE)
 * @param template qual impresso a loja usa (TD, DAM, RDAM, CPL)
 */
public record LojaForm(
        @NotNull @Positive Integer numero,
        @NotBlank @Size(max = 120) String nome,
        @NotBlank String cnpj,
        @NotBlank @Size(max = 40) String empresa,
        @NotNull TemplateCodigo template) {
}
