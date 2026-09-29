package br.com.rdamasio.helpagent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Todas as migrations Flyway num <b>PostgreSQL de verdade</b> (os outros testes usam H2 em modo PostgreSQL, que
 * aceita coisas que o PostgreSQL recusa e vice-versa). Só roda com {@code TESTE_POSTGRES_URL} definida — o CI sobe
 * um PostgreSQL para isso (.github/workflows/ci.yml); na máquina local ele é pulado.
 *
 * <p>É a garantia, para quem integrar numa aplicação maior com PostgreSQL, de que o schema sobe do zero.
 */
@EnabledIfEnvironmentVariable(named = "TESTE_POSTGRES_URL", matches = ".+")
@SpringBootTest(properties = {"helpagent.seguranca.habilitada=false", "helpagent.gemini.api-key="})
class MigracoesPostgresTest {

    @DynamicPropertySource
    static void banco(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> System.getenv("TESTE_POSTGRES_URL"));
        r.add("spring.datasource.username", () -> System.getenv().getOrDefault("TESTE_POSTGRES_USUARIO", "helpagent"));
        r.add("spring.datasource.password", () -> System.getenv().getOrDefault("TESTE_POSTGRES_SENHA", "helpagent"));
    }

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void schemaSobeESeedDasLojasEntra() {
        Integer versao = jdbc.queryForObject(
                "select max(cast(version as integer)) from flyway_schema_history where success", Integer.class);
        assertThat(versao).isGreaterThanOrEqualTo(7);
        // V6 sobrescreve/insere as lojas com UPDATE + INSERT ... WHERE NOT EXISTS (sem FROM): conferir no PostgreSQL
        assertThat(jdbc.queryForObject("select count(*) from loja", Integer.class)).isGreaterThanOrEqualTo(55);
        assertThat(jdbc.queryForObject("select razao_social from loja where numero = 701", String.class))
                .isEqualTo("L MOURA MOTOPECAS EIRELI - ME");
        assertThat(jdbc.queryForObject("select count(*) from uso_ia", Integer.class)).isZero();
    }
}
