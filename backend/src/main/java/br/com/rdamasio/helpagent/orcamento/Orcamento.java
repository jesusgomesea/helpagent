package br.com.rdamasio.helpagent.orcamento;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import br.com.rdamasio.helpagent.loja.Loja;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

/** Um impresso gerado. É o registro do histórico — substitui o IndexedDB local da v3.5. */
@Entity
@Table(name = "orcamento")
public class Orcamento {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ModoAquisicao modo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private OrigemOrcamento origem = OrigemOrcamento.DOCUMENTOS;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "loja_id")
    private Loja loja;

    @Column(nullable = false, length = 200)
    private String titulo;

    @Column(name = "data_emissao", nullable = false)
    private LocalDate dataEmissao;

    /** "Válido até" impresso no formulário; null quando o orçamento não informa validade. */
    @Column
    private LocalDate validade;

    @Column(name = "chamado_num", length = 100)
    private String chamadoNum;

    @Column(precision = 14, scale = 2)
    private BigDecimal subtotal;

    @Column(precision = 14, scale = 2)
    private BigDecimal frete;

    @Column(precision = 14, scale = 2)
    private BigDecimal acrescimos;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal total;

    @Column(length = 4000)
    private String observacoes;

    @Column(nullable = false, length = 120)
    private String requerente;

    @Column(nullable = false, length = 120)
    private String gestor;

    @Column(name = "nome_arquivo", nullable = false, length = 255)
    private String nomeArquivo;

    @Column(name = "pdf_ref", nullable = false, length = 500)
    private String pdfRef;

    @Column(name = "criado_por", nullable = false, length = 120)
    private String criadoPor;

    @Column(name = "criado_em", nullable = false)
    private Instant criadoEm;

    @OneToMany(mappedBy = "orcamento", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("ordem")
    private List<OrcamentoItem> itens = new ArrayList<>();

    protected Orcamento() {
    }

    public Orcamento(ModoAquisicao modo, Loja loja, String titulo, LocalDate dataEmissao, String chamadoNum,
            BigDecimal subtotal, BigDecimal frete, BigDecimal acrescimos, BigDecimal total, String observacoes,
            String requerente, String gestor, String criadoPor, Instant criadoEm) {
        this.modo = modo;
        this.loja = loja;
        this.titulo = titulo;
        this.dataEmissao = dataEmissao;
        this.chamadoNum = chamadoNum;
        this.subtotal = subtotal;
        this.frete = frete;
        this.acrescimos = acrescimos;
        this.total = total;
        this.observacoes = observacoes;
        this.requerente = requerente;
        this.gestor = gestor;
        this.criadoPor = criadoPor;
        this.criadoEm = criadoEm;
    }

    public void adicionarItem(OrcamentoItem item) {
        item.vincular(this);
        itens.add(item);
    }

    public void definirValidade(LocalDate validade) {
        this.validade = validade;
    }

    public void definirOrigem(OrigemOrcamento origem) {
        this.origem = origem == null ? OrigemOrcamento.DOCUMENTOS : origem;
    }

    public void registrarPdf(String nomeArquivo, String pdfRef) {
        this.nomeArquivo = nomeArquivo;
        this.pdfRef = pdfRef;
    }

    public Long getId() { return id; }
    public ModoAquisicao getModo() { return modo; }
    public OrigemOrcamento getOrigem() { return origem; }
    public Loja getLoja() { return loja; }
    public String getTitulo() { return titulo; }
    public LocalDate getDataEmissao() { return dataEmissao; }
    public LocalDate getValidade() { return validade; }
    public String getChamadoNum() { return chamadoNum; }
    public BigDecimal getSubtotal() { return subtotal; }
    public BigDecimal getFrete() { return frete; }
    public BigDecimal getAcrescimos() { return acrescimos; }
    public BigDecimal getTotal() { return total; }
    public String getObservacoes() { return observacoes; }
    public String getRequerente() { return requerente; }
    public String getGestor() { return gestor; }
    public String getNomeArquivo() { return nomeArquivo; }
    public String getPdfRef() { return pdfRef; }
    public String getCriadoPor() { return criadoPor; }
    public Instant getCriadoEm() { return criadoEm; }
    public List<OrcamentoItem> getItens() { return itens; }
}
