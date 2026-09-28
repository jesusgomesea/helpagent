package br.com.rdamasio.helpagent.orcamento;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Linha do impresso. {@code valorTotal} é sempre recalculado no servidor (qtd × unitário).
 */
@Entity
@Table(name = "orcamento_item")
public class OrcamentoItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "orcamento_id")
    private Orcamento orcamento;

    @Column(nullable = false)
    private int ordem;

    @Column(nullable = false, length = 200)
    private String produto;

    @Column(length = 500)
    private String descricao;

    @Column(nullable = false, precision = 12, scale = 3)
    private BigDecimal quantidade;

    @Column(name = "valor_unitario", nullable = false, precision = 14, scale = 2)
    private BigDecimal valorUnitario;

    @Column(name = "valor_total", nullable = false, precision = 14, scale = 2)
    private BigDecimal valorTotal;

    protected OrcamentoItem() {
    }

    public OrcamentoItem(int ordem, String produto, String descricao, BigDecimal quantidade,
            BigDecimal valorUnitario, BigDecimal valorTotal) {
        this.ordem = ordem;
        this.produto = produto;
        this.descricao = descricao;
        this.quantidade = quantidade;
        this.valorUnitario = valorUnitario;
        this.valorTotal = valorTotal;
    }

    void vincular(Orcamento orcamento) {
        this.orcamento = orcamento;
    }

    public int getOrdem() { return ordem; }
    public String getProduto() { return produto; }
    public String getDescricao() { return descricao; }
    public BigDecimal getQuantidade() { return quantidade; }
    public BigDecimal getValorUnitario() { return valorUnitario; }
    public BigDecimal getValorTotal() { return valorTotal; }
}
