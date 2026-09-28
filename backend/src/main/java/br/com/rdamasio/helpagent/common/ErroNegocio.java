package br.com.rdamasio.helpagent.common;

import java.util.List;

/** Regra de negócio violada: o usuário consegue corrigir. Vira HTTP 422 com a lista de problemas. */
public class ErroNegocio extends RuntimeException {

    private final List<String> problemas;

    public ErroNegocio(String mensagem, List<String> problemas) {
        super(mensagem);
        this.problemas = List.copyOf(problemas);
    }

    public ErroNegocio(String mensagem) {
        this(mensagem, List.of());
    }

    public List<String> problemas() {
        return problemas;
    }
}
