package br.com.rdamasio.helpagent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Segurança da API.
 *
 * <ul>
 *   <li>{@code helpagent.seguranca.habilitada=false} (perfil local): tudo liberado, sem login.</li>
 *   <li>{@code true} (padrão): {@code /api/**} exige JWT do provedor OIDC corporativo — configurar
 *       {@code spring.security.oauth2.resourceserver.jwt.issuer-uri}, senão a aplicação não sobe.</li>
 * </ul>
 * O CORS só importa quando o frontend é servido de outra origem; atrás do proxy do {@code ng serve} não é usado.
 */
@Configuration
public class SegurancaConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, HelpAgentProperties props) throws Exception {
        http.csrf(csrf -> csrf.disable()) // API stateless com Bearer token: sem cookie de sessão, sem CSRF
                .cors(Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));

        if (!props.seguranca().habilitada()) {
            http.authorizeHttpRequests(a -> a.anyRequest().permitAll());
            return http.build();
        }

        http.authorizeHttpRequests(a -> a
                        .requestMatchers("/actuator/health/**").permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(o -> o.jwt(Customizer.withDefaults()));
        return http.build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(HelpAgentProperties props) {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(props.corsOrigens());
        cors.addAllowedMethod("*");
        cors.addAllowedHeader("*");
        // O frontend lê o nome do arquivo e o id do orçamento gerado nesses cabeçalhos.
        cors.addExposedHeader("Content-Disposition");
        cors.addExposedHeader("X-Orcamento-Id");
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cors);
        return source;
    }
}
