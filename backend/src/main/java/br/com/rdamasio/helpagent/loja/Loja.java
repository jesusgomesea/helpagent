package br.com.rdamasio.helpagent.loja;

import br.com.rdamasio.helpagent.template.TemplateCodigo;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Loja do grupo. Define qual impresso usar ({@link #getTemplate()}) e a empresa/CNPJ que saem nele.
 * O seed inicial vem de {@code V2__seed_lojas.sql}, gerado por {@code tools/extrair_legado.py}; razão social, IE,
 * cidade e UF chegaram no {@code V6__cadastro_completo_lojas.sql} (planilha de lojas do grupo). Esses quatro são
 * só cadastro: o impresso continua saindo com {@code empresa} e {@code cnpj}.
 */
@Entity
@Table(name = "loja")
public class Loja {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Número da loja como inteiro: "023" e "23" são a mesma loja. */
    @Column(nullable = false, unique = true)
    private int numero;

    @Column(nullable = false, length = 120)
    private String nome;

    @Column(nullable = false, length = 18)
    private String cnpj;

    /** Código da empresa impresso no formulário (ex.: DAMASIO-PE). */
    @Column(nullable = false, length = 40)
    private String empresa;

    @Enumerated(EnumType.STRING)
    @Column(name = "template_codigo", nullable = false, length = 10)
    private TemplateCodigo template;

    @Column(nullable = false)
    private boolean ativa = true;

    @Column(name = "razao_social", length = 120)
    private String razaoSocial;

    /** Como veio da planilha: com pontuação, ou "ISENTO". Vazio = não informada. */
    @Column(name = "inscricao_estadual", length = 20)
    private String inscricaoEstadual;

    @Column(length = 60)
    private String cidade;

    @Column(length = 2)
    private String uf;

    protected Loja() {
    }

    public Loja(int numero, String nome, String cnpj, String empresa, TemplateCodigo template) {
        this.numero = numero;
        this.nome = nome;
        this.cnpj = cnpj;
        this.empresa = empresa;
        this.template = template;
    }

    /** Dados cadastrais que não saem no impresso; nulos ficam em branco. */
    public void definirCadastro(String razaoSocial, String inscricaoEstadual, String cidade, String uf) {
        this.razaoSocial = razaoSocial;
        this.inscricaoEstadual = inscricaoEstadual;
        this.cidade = cidade;
        this.uf = uf;
    }

    /** O número não muda: é a chave que o helpdesk e a IA usam. Trocou de número → cadastrar outra e desativar esta. */
    public void atualizar(String nome, String cnpj, String empresa, TemplateCodigo template) {
        this.nome = nome;
        this.cnpj = cnpj;
        this.empresa = empresa;
        this.template = template;
    }

    /** Loja desativada some da busca e da geração, mas continua nos orçamentos antigos (não se apaga loja). */
    public void definirAtiva(boolean ativa) {
        this.ativa = ativa;
    }

    public Long getId() { return id; }
    public int getNumero() { return numero; }
    public String getNome() { return nome; }
    public String getCnpj() { return cnpj; }
    public String getEmpresa() { return empresa; }
    public TemplateCodigo getTemplate() { return template; }
    public boolean isAtiva() { return ativa; }
    public String getRazaoSocial() { return razaoSocial; }
    public String getInscricaoEstadual() { return inscricaoEstadual; }
    public String getCidade() { return cidade; }
    public String getUf() { return uf; }
}
