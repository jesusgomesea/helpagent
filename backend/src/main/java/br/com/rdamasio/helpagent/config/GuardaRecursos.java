package br.com.rdamasio.helpagent.config;

import java.util.List;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import br.com.rdamasio.helpagent.common.RecursoDesligado;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Barra as rotas de um recurso desligado em {@link Recursos}: a chamada vira 503 com a explicação (via
 * {@code TratadorErros}) em vez de estourar lá dentro — por exemplo, a cotação tentando abrir um Chrome que o
 * servidor não tem. Um lugar só, por prefixo de rota, para os controllers não precisarem saber disso.
 */
@Configuration
public class GuardaRecursos implements WebMvcConfigurer {

    private final Recursos recursos;

    public GuardaRecursos(Recursos recursos) {
        this.recursos = recursos;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(barrar(() -> recursos.cotacao(), "A cotação em lojas online está desligada neste servidor",
                "ela precisa de um Chrome com sessão de desktop (helpagent.recursos.cotacao / RECURSO_COTACAO)"))
                .addPathPatterns("/api/cotacao", "/api/cotacao/**");
        registry.addInterceptor(barrar(() -> recursos.ia(), "A leitura por IA está desligada neste servidor",
                "preencha o orçamento manualmente (helpagent.recursos.ia / RECURSO_IA)"))
                .addPathPatterns("/api/extracoes", "/api/extracoes/**", "/api/uso-ia", "/api/uso-ia/**");
    }

    private static HandlerInterceptor barrar(java.util.function.BooleanSupplier ligado, String mensagem, String detalhe) {
        return new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
                if (!ligado.getAsBoolean()) throw new RecursoDesligado(mensagem, List.of(detalhe));
                return true;
            }
        };
    }
}
