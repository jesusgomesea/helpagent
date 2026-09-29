package br.com.rdamasio.helpagent.usoia;

/**
 * Como terminou uma chamada ao Gemini. É o que decide o que acontece com o modelo no {@link ControleCotaIa}:
 * cota esgotada pausa, sobrecarga só "esfria", erro comum não mexe na cadeia.
 */
public enum Ocorrencia {
    OK,
    /** 503 ou tempo esgotado: capacidade do Google, passageira. */
    SOBRECARGA,
    /** 429 da cota por minuto (requisições ou tokens). */
    COTA_MINUTO,
    /** 429 da cota diária. */
    COTA_DIA,
    /** 404: o modelo não existe para esta chave. */
    INDISPONIVEL,
    /** Qualquer outro erro (500, rede, JSON quebrado, 400/403). */
    ERRO
}
