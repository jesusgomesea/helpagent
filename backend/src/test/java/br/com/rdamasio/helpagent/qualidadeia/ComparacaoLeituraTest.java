package br.com.rdamasio.helpagent.qualidadeia;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import br.com.rdamasio.helpagent.extracao.DadosExtraidos;
import br.com.rdamasio.helpagent.orcamento.GerarOrcamentoRequest;
import br.com.rdamasio.helpagent.orcamento.ModoAquisicao;

/** Regra de "corrigido" de cada tipo de campo: o que conta como o atendente ter mudado o que a IA leu. */
class ComparacaoLeituraTest {

    private static DadosExtraidos lido(String titulo, String chamado, String loja, String total, DadosExtraidos.Item... itens) {
        return new DadosExtraidos(chamado, loja, "DAMASIO PE", titulo, List.of(itens), total, "# 1021069. Manutenção.", "", "");
    }

    private static DadosExtraidos.Item itemLido(String produto, String qtd, String unit, String fornecedor) {
        return new DadosExtraidos.Item(produto, "desc", qtd, unit, unit, "pág. 1", fornecedor, "");
    }

    private static GerarOrcamentoRequest confirmado(ModoAquisicao modo, String titulo, String chamado, String loja,
            GerarOrcamentoRequest.Item... itens) {
        return new GerarOrcamentoRequest(modo, loja, titulo, LocalDate.of(2026, 9, 30), null, chamado, List.of(itens),
                null, null, null, "# 1021069. Manutenção.", "A", "B", "leitura-1");
    }

    private static GerarOrcamentoRequest.Item itemFinal(String produto, String qtd, String unit) {
        return new GerarOrcamentoRequest.Item(produto, "desc", new BigDecimal(qtd), new BigDecimal(unit));
    }

    private static Map<String, Boolean> porCampo(List<ComparacaoLeitura.Campo> campos) {
        return campos.stream().collect(Collectors.toMap(c -> c.campo() + "#" + c.item(), ComparacaoLeitura.Campo::corrigido));
    }

    @Test
    void nadaMudouNadaCorrigido() {
        var r = porCampo(ComparacaoLeitura.comparar(
                lido("Nobreak 1500VA", "1021069", "023", "R$ 1.500,00", itemLido("Nobreak", "1", "1.500,00", "KABUM COMERCIO ELETRONICO S.A.")),
                confirmado(ModoAquisicao.REQUISICAO, "NOBREAK 1500VA", "# 1021069", "23", itemFinal("Nobreak", "1", "1500.00")),
                new BigDecimal("1500.00"), List.of("Kabum")));

        assertThat(r).doesNotContainValue(true); // maiúsculas, "023" = "23", "#" no chamado, fornecedor padronizado
        assertThat(r).containsKeys("titulo#0", "chamado#0", "loja#0", "total#0", "produto#1", "valor_unitario#1", "fornecedor#1");
    }

    @Test
    void valorErradoEFornecedorTrocadoSaoCorrecoes() {
        var r = porCampo(ComparacaoLeitura.comparar(
                lido("Nobreak", "1021069", "23", "R$ 1.500,00", itemLido("Nobreak", "1", "1.500,00", "Loja A")),
                confirmado(ModoAquisicao.REQUISICAO, "Nobreak", "1021069", "23", itemFinal("Nobreak", "1", "1350.00")),
                new BigDecimal("1350.00"), List.of("Outra Loja")));

        assertThat(r).containsEntry("valor_unitario#1", true).containsEntry("total#0", true)
                .containsEntry("fornecedor#1", true).containsEntry("titulo#0", false);
    }

    @Test
    void itemAMaisContaEmNumeroDeItens() {
        var r = porCampo(ComparacaoLeitura.comparar(
                lido("X", "1021069", "23", "R$ 10,00", itemLido("A", "1", "10,00", "")),
                confirmado(ModoAquisicao.REQUISICAO, "X", "1021069", "23", itemFinal("A", "1", "10"), itemFinal("B", "1", "5")),
                new BigDecimal("15.00"), java.util.Arrays.asList(null, null)));

        assertThat(r).containsEntry("numero_de_itens#0", true);
        assertThat(r).doesNotContainKey("fornecedor#1"); // vazio dos dois lados não entra
    }

    @Test
    void capexNaoComparaChamadoNemLoja() {
        var r = porCampo(ComparacaoLeitura.comparar(
                lido("Servidor", "", "", "R$ 10,00", itemLido("Servidor", "1", "10,00", "")),
                confirmado(ModoAquisicao.CAPEX, "Servidor", null, "23", itemFinal("Servidor", "1", "10")),
                new BigDecimal("10.00"), java.util.Collections.singletonList(null)));

        assertThat(r).doesNotContainKeys("chamado#0", "loja#0");
    }
}
