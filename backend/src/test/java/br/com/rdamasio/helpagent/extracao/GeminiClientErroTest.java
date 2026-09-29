package br.com.rdamasio.helpagent.extracao;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

/**
 * Leitura do corpo de erro do Google (é dele que sai "dia ou minuto", o limite e o "tente de novo em") e a
 * config de raciocínio por família de modelo. Corpo copiado do 429 real de 25/09/2026, sem a chave.
 */
class GeminiClientErroTest {

    private static final String ERRO_429_DIA = """
            {"error":{"code":429,"message":"You exceeded your current quota ... limit: 20, model: gemini-3.6-flash",
             "status":"RESOURCE_EXHAUSTED","details":[
              {"@type":"type.googleapis.com/google.rpc.QuotaFailure","violations":[
                {"quotaMetric":"generativelanguage.googleapis.com/generate_content_free_tier_requests",
                 "quotaId":"GenerateRequestsPerDayPerProjectPerModel-FreeTier",
                 "quotaDimensions":{"location":"global","model":"gemini-3.6-flash"},"quotaValue":"20"}]},
              {"@type":"type.googleapis.com/google.rpc.Help","links":[]},
              {"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"7.581926528s"}]}}
            """;

    @Test
    void lêCotaDiariaLimiteETempoDeEspera() {
        var erro = JsonMapper.builder().build().readValue(ERRO_429_DIA, GeminiClient.CorpoErro.class).error();

        assertThat(erro.violouCotaDiaria()).isTrue();
        assertThat(erro.limite()).isEqualTo(20);
        assertThat(erro.tentarDepois()).isEqualTo(Duration.ofMillis(7_581));
    }

    @Test
    void raciocinioPorFamiliaDeModelo() {
        assertThat(GeminiClient.configRaciocinio("gemini-3.6-flash", "low")).isEqualTo(Map.of("thinkingLevel", "low"));
        // 2.x recusa thinkingLevel (400): usa thinkingBudget, 0 = sem raciocínio
        assertThat(GeminiClient.configRaciocinio("gemini-2.5-flash", "low")).isEqualTo(Map.of("thinkingBudget", 0));
        assertThat(GeminiClient.configRaciocinio("gemini-2.5-flash-lite", "high")).isEqualTo(Map.of("thinkingBudget", -1));
    }
}
