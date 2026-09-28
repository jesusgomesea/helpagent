package br.com.rdamasio.helpagent.extracao;

import java.time.LocalDate;

/** Expõe aos testes de outros pacotes a regra de validade, que é package-private em ExtracaoService. */
public final class ExtracaoServiceAcesso {

    private ExtracaoServiceAcesso() {
    }

    public static LocalDate validade(DadosExtraidos d, LocalDate hoje) {
        return ExtracaoService.validadeSugerida(d, hoje);
    }
}
