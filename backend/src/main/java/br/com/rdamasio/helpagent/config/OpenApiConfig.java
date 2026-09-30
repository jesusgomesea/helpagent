package br.com.rdamasio.helpagent.config;

import java.util.Optional;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

/**
 * Contrato da API para quem for integrar este sistema numa aplicação maior (docs/INTEGRACAO.md): o springdoc gera o
 * OpenAPI dos controllers em {@code /v3/api-docs} e a tela {@code /swagger-ui.html}. É o código que manda — mudou um
 * controller, o contrato muda junto; não há arquivo à mão para ficar desatualizado.
 *
 * <p>Com a segurança ligada, o contrato declara o Bearer JWT do provedor OIDC (o mesmo que {@code SegurancaConfig}
 * exige), para o cliente gerado a partir dele já mandar o token.
 */
@Configuration
public class OpenApiConfig {

    static final String ESQUEMA = "oidc";

    @Bean
    OpenAPI contratoHelpAgent(HelpAgentProperties props, ObjectProvider<BuildProperties> build) {
        String versao = Optional.ofNullable(build.getIfAvailable()).map(BuildProperties::getVersion).orElse("dev");
        OpenAPI api = new OpenAPI().info(new Info()
                .title("HELP-AGENT Orçamentos")
                .version(versao)
                .description("Leitura de orçamentos por IA, geração do impresso oficial, histórico, cadastro de lojas, "
                        + "cotação em lojas online e uso da IA (painel técnico em /swagger/uso-ia). Erros no formato "
                        + "RFC 9457 (ProblemDetail) com `problemas` e `idRequisicao`; o cabeçalho X-Request-Id segue a "
                        + "chamada nos logs."));
        if (props.seguranca().habilitada()) {
            api.components(new Components().addSecuritySchemes(ESQUEMA, new SecurityScheme()
                            .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")
                            .description("Token do provedor OIDC corporativo")))
                    .addSecurityItem(new SecurityRequirement().addList(ESQUEMA));
        }
        return api;
    }
}
