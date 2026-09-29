package br.com.rdamasio.helpagent;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Servidor sem cotação e sem IA (ex.: contêiner sem desktop e sem saída para a internet): as rotas desses recursos
 * respondem 503 explicando, o núcleo (lojas, histórico, parâmetros) segue normal e {@code /api/parametros} avisa o
 * frontend do que está desligado.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:desligados;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "helpagent.seguranca.habilitada=false",
        "helpagent.gemini.api-key=",
        "helpagent.recursos.ia=false",
        "helpagent.recursos.cotacao=false"
})
@AutoConfigureMockMvc
class RecursosDesligadosTest {

    @Autowired
    MockMvc mvc;

    @Test
    void rotasDosRecursosDesligadosRespondem503() throws Exception {
        mvc.perform(get("/api/cotacao/estado")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.problemas[0]").exists());
        mvc.perform(multipart("/api/extracoes").param("modo", "CAPEX")).andExpect(status().isServiceUnavailable());
        mvc.perform(get("/api/uso-ia")).andExpect(status().isServiceUnavailable());
    }

    @Test
    void nucleoSegueEParametrosAvisamOFrontend() throws Exception {
        mvc.perform(get("/api/lojas")).andExpect(status().isOk());
        mvc.perform(get("/api/historico")).andExpect(status().isOk());
        mvc.perform(get("/api/parametros")).andExpect(status().isOk())
                .andExpect(jsonPath("$.recursos.ia").value(false))
                .andExpect(jsonPath("$.recursos.cotacao").value(false));
    }
}
