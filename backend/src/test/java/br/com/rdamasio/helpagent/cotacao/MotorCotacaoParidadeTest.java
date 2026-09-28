package br.com.rdamasio.helpagent.cotacao;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * O motor Java decide igual ao motor.py do piloto: mesma ordem, scores, parciais, motivos e grupos.
 * Gabarito gerado por {@code tools/paridade_cotacao.py} sobre uma coleta real ({@code amostra-ssd-256gb.json}).
 * Motivos são comparados sem acento e com vírgula decimal = ponto (o piloto escrevia "nao", "4.5").
 */
class MotorCotacaoParidadeTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** Mesma sequência de tools/paridade_cotacao.py (VOLUMES). */
    private static final Integer[] VOLUMES = {null, 0, 30, 150, 800, 3000, 12000};

    static Stream<JsonNode> cenarios() throws Exception {
        List<JsonNode> lista = new ArrayList<>();
        ler("/cotacao/paridade.json").forEach(lista::add);
        return lista.stream();
    }

    @ParameterizedTest(name = "cenário {index}")
    @MethodSource("cenarios")
    void igualAoPiloto(JsonNode cen) throws Exception {
        List<Anuncio> amostra = new ArrayList<>(Arrays.asList(
                JSON.treeToValue(ler("/cotacao/amostra-ssd-256gb.json"), Anuncio[].class)));
        if (cen.get("volumeSintetico").asBoolean()) {
            for (int i = 0; i < amostra.size(); i++) {
                Anuncio a = amostra.get(i);
                amostra.set(i, new Anuncio(a.titulo(), a.preco(), a.precoDe(), a.nota(), VOLUMES[i % VOLUMES.length],
                        a.full(), a.lojaOficial(), a.freteGratis(), a.recondicionado(), a.internacional(), a.pais(),
                        a.vendedor(), a.url(), a.patrocinado(), a.fonte(), a.vendedorProprio()));
            }
        }
        CriteriosCotacao criterios = JSON.treeToValue(cen.get("criterios"), CriteriosCotacao.class);

        ResultadoCotacao r = new MotorCotacao().avaliar(amostra, criterios);

        JsonNode eleg = cen.get("elegiveis");
        assertThat(r.elegiveis()).hasSize(eleg.size());
        for (int i = 0; i < eleg.size(); i++) {
            JsonNode e = eleg.get(i);
            var a = r.elegiveis().get(i);
            String onde = cen.get("nome").asString() + " #" + i + " " + e.get("titulo").asString();
            assertThat(a.anuncio().titulo()).as(onde).isEqualTo(e.get("titulo").asString());
            assertThat(a.score()).as(onde).isEqualTo(e.get("score").asDouble());
            assertThat(a.tier()).as(onde).isEqualTo(e.get("tier").asString());
            assertThat(a.segmento()).as(onde).isEqualTo(e.get("segmento").isNull() ? null : e.get("segmento").asString());
            JsonNode p = e.get("parciais");
            assertThat(a.parciais()).as(onde).isEqualTo(new ResultadoCotacao.Parciais(p.get("preco").asInt(),
                    p.get("entrega").asInt(), p.get("fornecedor").asInt(), p.get("marca").asInt()));
        }

        JsonNode desc = cen.get("descartados");
        assertThat(r.descartados()).hasSize(desc.size());
        for (int i = 0; i < desc.size(); i++) {
            assertThat(r.descartados().get(i).anuncio().titulo()).isEqualTo(desc.get(i).get("titulo").asString());
            assertThat(semAcento(r.descartados().get(i).motivo())).isEqualTo(semAcento(desc.get(i).get("motivo").asString()));
        }

        JsonNode grupos = cen.get("grupos");
        assertThat(r.grupos()).hasSize(grupos.size());
        for (int i = 0; i < grupos.size(); i++) {
            JsonNode g = grupos.get(i);
            var gj = r.grupos().get(i);
            assertThat(gj.rotulo()).isEqualTo(g.get("rotulo").asString());
            assertThat(gj.qtd()).isEqualTo(g.get("qtd").asInt());
            assertThat(gj.menorPreco()).isEqualTo(g.get("menorPreco").asDouble());
            assertThat(gj.precoMedio()).isEqualTo(g.get("precoMedio").asDouble());
            List<String> top3 = new ArrayList<>();
            g.get("top3").forEach(t -> top3.add(t.asString()));
            assertThat(gj.top3()).extracting(x -> x.anuncio().titulo()).containsExactlyElementsOf(top3);
        }

        JsonNode res = cen.get("resumo");
        assertThat(r.resumo().mediana()).isEqualTo(res.get("mediana").asDouble());
        assertThat(r.resumo().precoMedio()).isEqualTo(res.get("precoMedio").asDouble());
        assertThat(r.resumo().semVolume()).isEqualTo(res.get("semVolume").asInt());
        JsonNode pe = res.get("pesosEfetivos");
        assertThat(r.resumo().pesosEfetivos()).containsExactly(
                java.util.Map.entry("preco", pe.get("peso_preco").asInt()),
                java.util.Map.entry("entrega", pe.get("peso_entrega").asInt()),
                java.util.Map.entry("fornecedor", pe.get("peso_fornecedor").asInt()),
                java.util.Map.entry("marca", pe.get("peso_marca").asInt()));
    }

    private static String semAcento(String s) {
        return MotorCotacao.norm(s).replace(',', '.');
    }

    private static JsonNode ler(String recurso) throws Exception {
        try (InputStream in = MotorCotacaoParidadeTest.class.getResourceAsStream(recurso)) {
            return JSON.readTree(in);
        }
    }
}
