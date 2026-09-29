package br.com.rdamasio.helpagent.usoia;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.stereotype.Component;

import br.com.rdamasio.helpagent.config.HelpAgentProperties;

/**
 * Saúde da leitura por IA em {@code /actuator/health} (componente "ia"): chave configurada e situação de cada modelo
 * da cadeia. Nunca derruba a saúde geral — sem IA o sistema continua gerando orçamento à mão e cotando — por isso,
 * sem chave ou sem nenhum modelo com vez, o estado é UNKNOWN (fica abaixo de UP na agregação do Spring), não DOWN.
 * O monitoramento da aplicação maior vê o detalhe e alerta se quiser.
 */
@Component("ia")
public class SaudeIa implements HealthIndicator {

    private final ControleCotaIa controle;
    private final boolean chaveConfigurada;

    public SaudeIa(ControleCotaIa controle, HelpAgentProperties props) {
        this.controle = controle;
        String chave = props.gemini() == null ? null : props.gemini().apiKey();
        this.chaveConfigurada = chave != null && !chave.isBlank();
    }

    @Override
    public Health health() {
        Map<String, Object> modelos = new LinkedHashMap<>();
        boolean algumComVez = false;
        for (ControleCotaIa.Situacao s : controle.situacao()) {
            modelos.put(s.modelo(), s.estado() + " (" + s.requisicoesDia() + "/" + s.rpd() + " hoje)");
            algumComVez |= s.estado() == ControleCotaIa.Estado.DISPONIVEL || s.estado() == ControleCotaIa.Estado.ESFRIANDO;
        }
        Status status = chaveConfigurada && algumComVez ? Status.UP : Status.UNKNOWN;
        return Health.status(status)
                .withDetail("chaveConfigurada", chaveConfigurada)
                .withDetail("cadeia", modelos)
                .build();
    }
}
