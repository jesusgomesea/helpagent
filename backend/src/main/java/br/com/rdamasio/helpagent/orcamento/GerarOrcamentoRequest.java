package br.com.rdamasio.helpagent.orcamento;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** Dados revisados pelo usuário. Valores monetários chegam como número (o frontend converte o BRL). */
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
        @Size(max = 120) String gestor) {

    public record Item(
            @Size(max = 200) String produto,
            @Size(max = 500) String descricao,
            @PositiveOrZero BigDecimal quantidade,
            @PositiveOrZero BigDecimal valorUnitario) {
    }
}
