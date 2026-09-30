package br.com.rdamasio.helpagent.qualidadeia;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** O que a IA leu numa extração (tabela {@code leitura_ia}, V10), guardado para comparar com o que foi confirmado. */
@Entity
@Table(name = "leitura_ia")
public class LeituraIa {

    /** O mesmo id que agrupa as chamadas da leitura em uso_ia. */
    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "criado_em", nullable = false)
    private Instant criadoEm;

    @Column(nullable = false, length = 12)
    private String modo;

    @Column(nullable = false, length = 60)
    private String modelo;

    @Column(nullable = false)
    private int degrau;

    @Column(nullable = false)
    private int arquivos;

    /** JSON de DadosExtraidos, como a IA devolveu. */
    @Column(nullable = false, length = 20000)
    private String dados;

    protected LeituraIa() {
    }

    public LeituraIa(String id, Instant criadoEm, String modo, String modelo, int degrau, int arquivos, String dados) {
        this.id = id;
        this.criadoEm = criadoEm;
        this.modo = modo;
        this.modelo = modelo;
        this.degrau = degrau;
        this.arquivos = arquivos;
        this.dados = dados;
    }

    public String getId() { return id; }
    public Instant getCriadoEm() { return criadoEm; }
    public String getModo() { return modo; }
    public String getModelo() { return modelo; }
    public int getDegrau() { return degrau; }
    public int getArquivos() { return arquivos; }
    public String getDados() { return dados; }
}
