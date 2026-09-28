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
 * O seed inicial vem de {@code V2__seed_lojas.sql}, gerado por {@code tools/extrair_legado.py}.
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

    protected Loja() {
    }

    public Loja(int numero, String nome, String cnpj, String empresa, TemplateCodigo template) {
        this.numero = numero;
        this.nome = nome;
        this.cnpj = cnpj;
        this.empresa = empresa;
        this.template = template;
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
}
