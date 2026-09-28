package br.com.rdamasio.helpagent.extracao;

/**
 * Falha ao falar com a IA.
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

    public boolean cotaDiaria() { return cotaDiaria; }
    public boolean sobrecarga() { return sobrecarga; }
    public int status() { return status; }
    public boolean retentavel() { return retentavel; }
    public int tentativas() { return tentativas; }

    FalhaIa comTentativas(int n) {
        this.tentativas = n;
        return this;
    }
}
