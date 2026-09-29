package br.com.rdamasio.helpagent;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * A aplicação inteira sobe (todas as migrations Flyway num H2 em memória, todos os beans) e os pontos que uma
 * aplicação maior usa para integrar respondem: saúde/sondas, contrato OpenAPI, id de requisição e o formato de erro.
 * É o teste que pega dependência incompatível ou migration quebrada antes de ir para o servidor (roda no CI).
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:sobe;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "helpagent.seguranca.habilitada=false",
        "helpagent.gemini.api-key=",
        "management.endpoints.web.exposure.include=health,info,prometheus"
})
@AutoConfigureMockMvc
class AplicacaoSobeTest {

    @Autowired
    MockMvc mvc;

    @Test
    void saudeESondasRespondem() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
    }

    @Test
    void contratoOpenApiListaAsRotas() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("HELP-AGENT Orçamentos"))
                .andExpect(jsonPath("$.paths['/api/extracoes']").exists())
                .andExpect(jsonPath("$.paths['/api/uso-ia']").exists());
    }

    @Test
    void idDeRequisicaoVoltaNoCabecalhoENoErro() throws Exception {
        mvc.perform(get("/api/lojas/999999").header("X-Request-Id", "integracao-1"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("X-Request-Id", "integracao-1"))
                .andExpect(jsonPath("$.idRequisicao").value("integracao-1"));
        // id malformado (poderia forjar linha de log) é trocado por um gerado
        mvc.perform(get("/api/lojas/999999").header("X-Request-Id", "a b\nc"))
                .andExpect(header().string("X-Request-Id", notNullValue()))
                .andExpect(jsonPath("$.idRequisicao").value(org.hamcrest.Matchers.not("a b\nc")));
    }

    @Test
    void parametrosEMetricasDaIa() throws Exception {
        mvc.perform(get("/api/parametros")).andExpect(status().isOk());
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isOk());
        mvc.perform(get("/api/lojas")).andExpect(status().isOk()).andExpect(jsonPath("$[*].numero", hasItem(23)));
    }
}
