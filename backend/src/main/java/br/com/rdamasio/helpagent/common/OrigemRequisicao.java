package br.com.rdamasio.helpagent.common;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Quem fez a requisição, para os logs de auditoria (lojas, cotação). Sem login, o que há é o IP. Atrás do proxy
 * do ng serve o IP direto é 127.0.0.1; o do atendente vem no X-Forwarded-For (ver "xfwd" em frontend/proxy.conf.json).
 */
public final class OrigemRequisicao {

    private OrigemRequisicao() {
    }

    public static String de(HttpServletRequest req) {
        String encaminhado = req.getHeader("X-Forwarded-For");
        return encaminhado != null && !encaminhado.isBlank() ? encaminhado.split(",")[0].trim() : req.getRemoteAddr();
    }
}
