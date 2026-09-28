package br.com.rdamasio.helpagent.cotacao;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Regras que entraram com as lojas além do Mercado Livre: loja própria sem nota não é eliminada (reputação
 * presumida), e o mesmo produto em lojas diferentes não é tratado como duplicado.
 */
class LojaPropriaTest {

    private static Anuncio anuncio(String titulo, double preco, Double nota, String fonte, boolean proprio) {
        return new Anuncio(titulo, preco, null, nota, null, false, proprio, false, false, false, "", fonte, "https://x/" +
                fonte, false, fonte, proprio);
    }

    private static CriteriosCotacao lojaPropria(boolean aceitar) {
        return new CriteriosCotacao(null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, aceitar);
    }

    @Test
    void lojaPropriaSemNotaFicaComReputacaoPresumida() {
        var pichau = anuncio("SSD Kingston 256GB", 300, null, "Pichau", true);
        var mercado = anuncio("SSD Kingston 256GB", 280, null, "Mercado Livre", false);

        ResultadoCotacao r = new MotorCotacao().avaliar(List.of(pichau, mercado), lojaPropria(true));

        assertThat(r.elegiveis()).extracting(a -> a.anuncio().fonte()).containsExactly("Pichau");
        // nota presumida 4,5 → 0,5 da nota + 0,10 de loja oficial = 60 pontos de fornecedor
        assertThat(r.elegiveis().getFirst().parciais().fornecedor()).isEqualTo(60);
        assertThat(r.descartados()).singleElement()
                .satisfies(d -> assertThat(d.motivo()).isEqualTo("vendedor sem avaliação pública"));
    }

    @Test
    void criterioDesligadoVoltaARegraDoPiloto() {
        var pichau = anuncio("SSD Kingston 256GB", 300, null, "Pichau", true);

        ResultadoCotacao r = new MotorCotacao().avaliar(List.of(pichau), lojaPropria(false));

        assertThat(r.elegiveis()).isEmpty();
        assertThat(r.descartados()).singleElement()
                .satisfies(d -> assertThat(d.motivo()).isEqualTo("vendedor sem avaliação pública"));
    }

    @Test
    void notaBaixaDaLojaPropriaContinuaEliminando() {
        var kabum = anuncio("SSD Kingston 256GB", 300, 3.9, "Kabum", true);

        ResultadoCotacao r = new MotorCotacao().avaliar(List.of(kabum), lojaPropria(true));

        assertThat(r.elegiveis()).isEmpty(); // a presunção vale só para a falta de nota, não para nota ruim
    }

    @Test
    void mesmoTituloEmLojasDiferentesNaoEDuplicado() {
        var kabum = anuncio("SSD Kingston 256GB", 300, 4.8, "Kabum", true);
        var kabumCaro = anuncio("SSD Kingston 256GB", 350, 4.8, "Kabum", true);
        var pichau = anuncio("SSD Kingston 256GB", 310, null, "Pichau", true);

        List<Anuncio> limpos = ColetorCotacao.semDuplicados(List.of(kabum, kabumCaro, pichau));

        assertThat(limpos).extracting(Anuncio::fonte, Anuncio::preco)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("Kabum", 300.0),
                        org.assertj.core.groups.Tuple.tuple("Pichau", 310.0));
    }
}
