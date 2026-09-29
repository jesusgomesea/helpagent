package br.com.rdamasio.helpagent.usoia;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Uma requisição enviada ao Gemini (tabela {@code uso_ia}, V7). Só grava; quem lê é o painel "Uso da IA" e a
 * recarga dos contadores do {@link ControleCotaIa} quando o servidor sobe.
 */
@Entity
@Table(name = "uso_ia")
public class UsoIa {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Instant momento;

    /** Agrupa as chamadas de uma mesma extração. */
    @Column(nullable = false, length = 36)
    private String leitura;

    @Column(nullable = false, length = 60)
    private String modelo;

    /** Posição do modelo na cadeia (0 = melhor); -1 = fora da cadeia (modelo forçado). */
    @Column(nullable = false)
    private int degrau;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Papel papel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Ocorrencia ocorrencia;

    /** 0 = sem resposta HTTP (rede ou tempo esgotado). */
    @Column(name = "status_http", nullable = false)
    private int statusHttp;

    @Column(name = "tokens_entrada")
    private Integer tokensEntrada;

    @Column(name = "tokens_saida")
    private Integer tokensSaida;

    @Column(name = "tokens_raciocinio")
    private Integer tokensRaciocinio;

    /** Tokens de entrada estimados antes da chamada (o que o controle reservou). */
    @Column(nullable = false)
    private int estimativa;

    @Column(name = "duracao_ms", nullable = false)
    private long duracaoMs;

    @Column(length = 12)
    private String modo;

    @Column(nullable = false)
    private int arquivos;

    /** IP de quem pediu a leitura (não há login). */
    @Column(length = 60)
    private String origem;

    protected UsoIa() {
    }

    public UsoIa(Instant momento, String leitura, String modelo, int degrau, Papel papel, Ocorrencia ocorrencia,
            int statusHttp, Integer tokensEntrada, Integer tokensSaida, Integer tokensRaciocinio, int estimativa,
            long duracaoMs, String modo, int arquivos, String origem) {
        this.momento = momento;
        this.leitura = leitura;
        this.modelo = modelo;
        this.degrau = degrau;
        this.papel = papel;
        this.ocorrencia = ocorrencia;
        this.statusHttp = statusHttp;
        this.tokensEntrada = tokensEntrada;
        this.tokensSaida = tokensSaida;
        this.tokensRaciocinio = tokensRaciocinio;
        this.estimativa = estimativa;
        this.duracaoMs = duracaoMs;
        this.modo = modo;
        this.arquivos = arquivos;
        this.origem = origem;
    }

    public Long getId() { return id; }
    public Instant getMomento() { return momento; }
    public String getLeitura() { return leitura; }
    public String getModelo() { return modelo; }
    public int getDegrau() { return degrau; }
    public Papel getPapel() { return papel; }
    public Ocorrencia getOcorrencia() { return ocorrencia; }
    public int getStatusHttp() { return statusHttp; }
    public Integer getTokensEntrada() { return tokensEntrada; }
    public Integer getTokensSaida() { return tokensSaida; }
    public Integer getTokensRaciocinio() { return tokensRaciocinio; }
    public int getEstimativa() { return estimativa; }
    public long getDuracaoMs() { return duracaoMs; }
    public String getModo() { return modo; }
    public int getArquivos() { return arquivos; }
    public String getOrigem() { return origem; }

    /** Tokens de entrada que contam na cota: os do Google quando ele contou, senão a estimativa (ex.: 503). */
    public int getEstimativaOuReal() {
        return tokensEntrada != null ? tokensEntrada : estimativa;
    }
}
