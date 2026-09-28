package br.com.rdamasio.helpagent.cotacao;

import java.util.List;

/**
 * A loja não devolveu nenhum anúncio (termo sem resultado, bloqueio/verificação de conta, layout mudou).
 * Vira HTTP 502 com os avisos da coleta — o diagnóstico está em docs/MANUTENCAO.md §7.
 */
public class FalhaColeta extends RuntimeException {

    private final List<String> avisos;

    public FalhaColeta(String mensagem, List<String> avisos) {
        super(mensagem);
        this.avisos = List.copyOf(avisos);
    }

    public List<String> avisos() {
        return avisos;
    }
}
