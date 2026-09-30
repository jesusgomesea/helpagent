package br.com.rdamasio.helpagent.qualidadeia;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Um campo comparado entre a leitura da IA e o orçamento gerado (tabela {@code correcao_ia}, V10). Só grava; o
 * relatório agrega. {@code orcamentoId} vira null se o orçamento for apagado de vez — a estatística fica.
 */
@Entity
@Table(name = "correcao_ia")
public class CorrecaoIa {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "criado_em", nullable = false)
    private Instant criadoEm;

    @Column(name = "leitura_id", nullable = false, length = 36)
    private String leituraId;

    @Column(name = "orcamento_id")
    private Long orcamentoId;

    @Column(nullable = false, length = 60)
    private String modelo;

    @Column(nullable = false, length = 40)
    private String campo;

    /** 0 = cabeçalho; 1..n = linha. */
    @Column(nullable = false)
    private int item;

    @Column(length = 120)
    private String fornecedor;

    @Column(length = 1000)
    private String lido;

    /** "final" é palavra reservada em SQL: a coluna é final_. */
    @Column(name = "final_", length = 1000)
    private String confirmado;

    @Column(nullable = false)
    private boolean corrigido;

    protected CorrecaoIa() {
    }

    public CorrecaoIa(Instant criadoEm, String leituraId, Long orcamentoId, String modelo, ComparacaoLeitura.Campo c) {
        this.criadoEm = criadoEm;
        this.leituraId = leituraId;
        this.orcamentoId = orcamentoId;
        this.modelo = modelo;
        this.campo = c.campo();
        this.item = c.item();
        this.fornecedor = c.fornecedor() != null && c.fornecedor().length() > 120 ? c.fornecedor().substring(0, 120) : c.fornecedor();
        this.lido = c.lido();
        this.confirmado = c.confirmado();
        this.corrigido = c.corrigido();
    }

    public Instant getCriadoEm() { return criadoEm; }
    public String getLeituraId() { return leituraId; }
    public Long getOrcamentoId() { return orcamentoId; }
    public String getModelo() { return modelo; }
    public String getCampo() { return campo; }
    public int getItem() { return item; }
    public String getFornecedor() { return fornecedor; }
    public String getLido() { return lido; }
    public String getConfirmado() { return confirmado; }
    public boolean isCorrigido() { return corrigido; }
}
