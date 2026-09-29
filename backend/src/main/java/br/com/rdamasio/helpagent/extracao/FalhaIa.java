package br.com.rdamasio.helpagent.extracao;

import java.time.Duration;

/**
 * Falha ao falar com a IA. O tipo decide o que o {@link ExtratorIa} faz: repetir o mesmo modelo, descer um
 * degrau da cadeia ou desistir.
 *
 * @param status HTTP devolvido pela API (0 quando nem houve resposta, ex.: rede)
 * @param retentavel se vale tentar de novo igual (429, 5xx, rede, JSON malformado)
 */
public class FalhaIa extends RuntimeException {

    private final int status;
    private final boolean retentavel;
    private int tentativas = 1;
    private boolean sobrecarga;
    private boolean cotaDiaria;
    private boolean cotaPorMinuto;
    private boolean modeloIndisponivel;
    /** "Tente de novo em" que o Google manda no 429 (RetryInfo.retryDelay); null se não veio. */
    private Duration tentarDepois;
    /** Limite que o Google disse ter estourado (QuotaFailure.quotaValue, ex.: 20 por dia); null se não veio. */
    private Integer limiteInformado;

    public FalhaIa(String mensagem, int status, boolean retentavel) {
        super(mensagem);
        this.status = status;
        this.retentavel = retentavel;
    }

    public FalhaIa(String mensagem, int status, boolean retentavel, Throwable causa) {
        super(mensagem, causa);
        this.status = status;
        this.retentavel = retentavel;
    }

    /** Modelo sobrecarregado (503 ou tempo esgotado): trocar de modelo resolve melhor que insistir no mesmo. */
    public static FalhaIa sobrecarga(String mensagem, int status, Throwable causa) {
        FalhaIa f = new FalhaIa(mensagem, status, true, causa);
        f.sobrecarga = true;
        return f;
    }

    /**
     * 429 cuja cota violada é a DIÁRIA (quotaId "...PerDay..." no corpo do erro). A cota por minuto também
     * vem como 429 com as palavras "quota"/"free_tier" — por isso a decisão usa o campo estruturado, não o texto.
     */
    public static FalhaIa cotaDiaria(String mensagem) {
        FalhaIa f = new FalhaIa(mensagem, 429, false);
        f.cotaDiaria = true;
        return f;
    }

    /**
     * 429 da cota POR MINUTO (requisições ou tokens). Antes o sistema repetia o mesmo modelo 1,2 s depois — e
     * levava outro 429, que também conta requisição: era assim que uma rajada se alimentava. Agora desce de degrau
     * e o modelo descansa pelo tempo que o Google pediu.
     */
    public static FalhaIa cotaPorMinuto(String mensagem, Duration tentarDepois) {
        FalhaIa f = new FalhaIa(mensagem, 429, true);
        f.cotaPorMinuto = true;
        f.tentarDepois = tentarDepois;
        return f;
    }

    /** 404: o modelo não existe (ou saiu do ar) para esta chave. Desce de degrau e o tira da cadeia. */
    public static FalhaIa modeloIndisponivel(String mensagem) {
        FalhaIa f = new FalhaIa(mensagem, 404, false);
        f.modeloIndisponivel = true;
        return f;
    }

    FalhaIa comLimiteInformado(Integer limite, Duration tentarDepois) {
        this.limiteInformado = limite;
        if (tentarDepois != null) this.tentarDepois = tentarDepois;
        return this;
    }

    public boolean cotaDiaria() { return cotaDiaria; }
    public boolean cotaPorMinuto() { return cotaPorMinuto; }
    public boolean sobrecarga() { return sobrecarga; }
    public boolean modeloIndisponivel() { return modeloIndisponivel; }
    public Duration tentarDepois() { return tentarDepois; }
    public Integer limiteInformado() { return limiteInformado; }
    public int status() { return status; }
    public boolean retentavel() { return retentavel; }
    public int tentativas() { return tentativas; }

    FalhaIa comTentativas(int n) {
        this.tentativas = n;
        return this;
    }
}
