package br.com.rdamasio.helpagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Ponto de entrada. {@code @ConfigurationPropertiesScan} registra o {@link br.com.rdamasio.helpagent.config.HelpAgentProperties}
 * (prefixo {@code helpagent.*} do application.yml).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class HelpAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(HelpAgentApplication.class, args);
    }
}
