package br.com.rdamasio.helpagent.fornecedor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Fornecedor conhecido (tabela {@code fornecedor}, V9): quem emitiu um orçamento. {@code nome} é o padronizado, o
 * que o helpdesk quer ver; {@code apelidos} guarda os nomes com que ele já apareceu nos documentos — é por eles que
 * {@link ReconhecimentoFornecedor} acha o mesmo fornecedor na próxima leitura, mesmo sem CNPJ.
 */
@Entity
@Table(name = "fornecedor")
public class Fornecedor {

    static final String SEPARADOR = " | ";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 120)
    private String nome;

    /** Formatado (00.000.000/0000-00); único quando existe. */
    @Column(length = 18, unique = true)
    private String cnpj;

    @Column(length = 2000)
    private String apelidos;

    @Column(nullable = false)
    private boolean ativo = true;

    @Column(name = "criado_em", nullable = false)
    private Instant criadoEm;

    protected Fornecedor() {
    }

    public Fornecedor(String nome, String cnpj, Instant criadoEm) {
        this.nome = nome;
        this.cnpj = cnpj;
        this.criadoEm = criadoEm;
    }

    public void atualizar(String nome, String cnpj, List<String> apelidos) {
        this.nome = nome;
        this.cnpj = cnpj;
        this.apelidos = juntar(apelidos);
    }

    /**
     * Guarda mais um nome com que o fornecedor apareceu (se ainda não está, e se não é o próprio nome). Limite de
     * tamanho da coluna: os mais antigos saem.
     */
    public boolean aprenderApelido(String apelido) {
        if (apelido == null || apelido.isBlank()) return false;
        String a = apelido.strip();
        String chave = ReconhecimentoFornecedor.chave(a);
        if (chave.isEmpty() || chave.equals(ReconhecimentoFornecedor.chave(nome))) return false;
        List<String> lista = new ArrayList<>(getApelidos());
        if (lista.stream().anyMatch(x -> ReconhecimentoFornecedor.chave(x).equals(chave))) return false;
        lista.add(a.length() > 200 ? a.substring(0, 200) : a);
        while (juntar(lista).length() > 2000) lista.removeFirst();
        this.apelidos = juntar(lista);
        return true;
    }

    public void definirCnpjSeVazio(String cnpj) {
        if (this.cnpj == null && cnpj != null) this.cnpj = cnpj;
    }

    public void definirAtivo(boolean ativo) {
        this.ativo = ativo;
    }

    private static String juntar(List<String> lista) {
        if (lista == null || lista.isEmpty()) return null;
        String s = String.join(SEPARADOR, lista.stream().map(String::strip).filter(x -> !x.isEmpty()).toList());
        return s.isEmpty() ? null : s;
    }

    public Long getId() { return id; }
    public String getNome() { return nome; }
    public String getCnpj() { return cnpj; }
    public boolean isAtivo() { return ativo; }
    public Instant getCriadoEm() { return criadoEm; }

    public List<String> getApelidos() {
        if (apelidos == null || apelidos.isBlank()) return List.of();
        return Arrays.stream(apelidos.split("\\s*\\|\\s*")).filter(x -> !x.isBlank()).toList();
    }
}
