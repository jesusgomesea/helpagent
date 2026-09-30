package br.com.rdamasio.helpagent.orcamento;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * Dados revisados pelo usuário. Valores monetários chegam como número (o frontend converte o BRL).
 * {@code idLeitura}: a leitura da IA que preencheu a revisão (opcional) — o servidor compara o lido com o confirmado.
 */
public record GerarOrcamentoRequest(
        @NotNull ModoAquisicao modo,
        @NotBlank String lojaNumero,
        @NotBlank @Size(max = 200) String titulo,
        @NotNull LocalDate dataEmissao,
        LocalDate validade,
        @Size(max = 100) String chamadoNum,
        @NotNull @Valid List<Item> itens,
        @PositiveOrZero BigDecimal subtotal,
        @PositiveOrZero BigDecimal frete,
        @PositiveOrZero BigDecimal acrescimos,
        @Size(max = 4000) String observacoes,
        @Size(max = 120) String requerente,
        @Size(max = 120) String gestor,
        @Size(max = 36) String idLeitura) {

    /**
     * @param prints          só no orçamento por cotação: ids dos prints das opções do item, a escolhida primeiro
     *                        (ver {@code cotacao.PrintsCotacao}). Ausente = linha comum.
     * @param fornecedor      quem emitiu o orçamento desta linha, como o atendente confirmou (opcional)
     * @param fornecedorCnpj  CNPJ lido ou digitado (opcional)
     * @param fornecedorLido  nome como a IA leu no documento — vira apelido do fornecedor (opcional)
     */
    public record Item(
            @Size(max = 200) String produto,
            @Size(max = 500) String descricao,
            @PositiveOrZero BigDecimal quantidade,
            @PositiveOrZero BigDecimal valorUnitario,
            @Size(max = 3) List<String> prints,
            @Size(max = 120) String fornecedor,
            @Size(max = 20) String fornecedorCnpj,
            @Size(max = 200) String fornecedorLido) {

        public Item(String produto, String descricao, BigDecimal quantidade, BigDecimal valorUnitario) {
            this(produto, descricao, quantidade, valorUnitario, null, null, null, null);
        }

        public boolean cotado() {
            return prints != null && !prints.isEmpty();
        }
    }
}
