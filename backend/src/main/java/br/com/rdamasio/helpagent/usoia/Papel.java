package br.com.rdamasio.helpagent.usoia;

/** Por que a chamada foi feita, dentro de uma leitura. Mostra na tela quanto do gasto é resiliência. */
public enum Papel {
    /** A primeira chamada da leitura, no melhor modelo com vez. */
    PRINCIPAL,
    /** Disparada em paralelo porque a primeira passou de {@code reserva-apos} sem responder. */
    RESERVA,
    /** A anterior falhou por cota/sobrecarga e a leitura desceu para outro modelo. */
    DEGRAU,
    /** Erro transitório (500, rede, JSON quebrado): o mesmo modelo de novo. */
    REPETICAO
}
