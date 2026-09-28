package br.com.rdamasio.helpagent.config;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Valores padrão que o formulário de revisão precisa conhecer. */
@RestController
public class ParametrosController {

    public record Parametros(int maxItens, String requerentePadrao, String gestorPadrao) {
    }

    private final HelpAgentProperties props;

    public ParametrosController(HelpAgentProperties props) {
        this.props = props;
    }

    @GetMapping("/api/parametros")
    public Parametros parametros() {
        var o = props.orcamento();
        return new Parametros(o.maxItens(), o.requerentePadrao(), o.gestorPadrao());
    }
}
