package br.com.rdamasio.helpagent.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Valores padrão que o formulário de revisão precisa conhecer, e o nome do ambiente: a homologação
 * ({@code iniciar-homologacao.bat}) define {@code HELPAGENT_AMBIENTE} e a tela mostra uma faixa, para ninguém
 * gerar orçamento de verdade nela achando que é a produção. Também diz quais recursos estão ligados ({@link Recursos}):
 * o frontend esconde menu e telas do que este servidor não oferece.
 */
@RestController
public class ParametrosController {

    /**
     * @param ambiente "" na produção; "homologacao" no ambiente de testes
     * @param recursos o que está ligado neste servidor (ia, cotacao)
     */
    public record Parametros(int maxItens, String requerentePadrao, String gestorPadrao, String ambiente,
            Recursos recursos) {
    }

    private final HelpAgentProperties props;
    private final Recursos recursos;
    private final String ambiente;

    public ParametrosController(HelpAgentProperties props, Recursos recursos,
            @Value("${helpagent.ambiente:}") String ambiente) {
        this.props = props;
        this.recursos = recursos;
        this.ambiente = ambiente;
    }

    @GetMapping("/api/parametros")
    public Parametros parametros() {
        var o = props.orcamento();
        return new Parametros(o.maxItens(), o.requerentePadrao(), o.gestorPadrao(), ambiente, recursos);
    }
}
