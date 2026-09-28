package br.com.rdamasio.helpagent.config;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/** Quem está gerando o orçamento — vai para o histórico como trilha de auditoria. */
@Component
public class UsuarioAtual {

    public String identificador() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getPrincipal() == null) {
            return "anonimo";
        }
        if (auth.getPrincipal() instanceof Jwt jwt) {
            // Entra ID e Keycloak usam claims diferentes para o login legível.
            for (String claim : new String[] {"preferred_username", "upn", "email"}) {
                String valor = jwt.getClaimAsString(claim);
                if (valor != null && !valor.isBlank()) return valor;
            }
            return jwt.getSubject();
        }
        String nome = auth.getName();
        return "anonymousUser".equals(nome) ? "anonimo" : nome;
    }
}
