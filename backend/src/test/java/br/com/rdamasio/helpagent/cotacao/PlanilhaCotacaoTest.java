package br.com.rdamasio.helpagent.cotacao;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import br.com.rdamasio.helpagent.config.HelpAgentProperties;
import tools.jackson.databind.json.JsonMapper;

/** A planilha abre, tem as 4 abas do formato acordado com Suprimentos e links clicáveis. */
class PlanilhaCotacaoTest {

    @Test
    void quatroAbasComTop3ELinks() throws Exception {
        List<Anuncio> amostra;
        try (InputStream in = getClass().getResourceAsStream("/cotacao/amostra-ssd-256gb.json")) {
            amostra = Arrays.asList(JsonMapper.builder().build().readValue(in, Anuncio[].class));
        }
        ResultadoCotacao r = new MotorCotacao().avaliar(amostra,
                new CriteriosCotacao(null, null, null, null, null, null, null, null, null, null, null, null, null,
                        null, null, null, "M.2, SATA", null));
        var g = new CotacaoService.Guardada("ssd 256gb", Instant.parse("2026-09-28T15:00:00Z"), 5.0, List.of(),
                java.util.Map.of("Mercado Livre", amostra.size()), amostra, r, Instant.MAX);
        var props = new HelpAgentProperties(null, null, null, null, null, ZoneId.of("America/Sao_Paulo"), List.of());

        byte[] xlsx = new PlanilhaCotacao(props).gerar(g);

        try (var wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            assertThat(wb.getNumberOfSheets()).isEqualTo(4);
            assertThat(List.of(wb.getSheetName(0), wb.getSheetName(1), wb.getSheetName(2), wb.getSheetName(3)))
                    .containsExactly("Resumo", "Critérios", "Análise", "Descartados");

            var resumo = wb.getSheet("Resumo");
            assertThat(resumo.getRow(0).getCell(0).getStringCellValue()).isEqualTo("COTAÇÃO  -  SSD 256GB");
            assertThat(resumo.getRow(3).getCell(2).getStringCellValue()).isEqualTo("Mercado Livre");
            assertThat(resumo.getRow(4).getCell(2).getStringCellValue()).isEqualTo("28/09/2026");
            // 1º grupo (M.2): título na linha 13 do Excel, cabeçalho na 14, 1º colocado na 15
            assertThat(resumo.getRow(12).getCell(0).getStringCellValue()).startsWith("TOP 3  -  M.2");
            var primeiro = resumo.getRow(14);
            assertThat(primeiro.getCell(1).getStringCellValue()).isEqualTo(r.grupos().getFirst().top3().getFirst().anuncio().titulo());
            assertThat(primeiro.getCell(2).getStringCellValue()).isEqualTo("Mercado Livre");
            assertThat(primeiro.getCell(8).getHyperlink().getAddress()).startsWith("https://");

            assertThat(wb.getSheet("Análise").getLastRowNum()).isEqualTo(r.elegiveis().size());
            assertThat(wb.getSheet("Descartados").getLastRowNum()).isEqualTo(2 + r.descartados().size());
        }
    }
}
