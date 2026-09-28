package br.com.rdamasio.helpagent.cotacao;

import java.util.List;
import java.util.Map;

/**
 * Saída do {@link MotorCotacao}: o que a tela desenha e a planilha exporta.
 *
 * @param elegiveis   passaram nos eliminatórios, em ordem de score (maior primeiro)
 * @param descartados cortados antes do score, cada um com o motivo
 * @param grupos      um bloco por segmento (ou um só, "Todos os resultados"), cada um com seu top 3
 * @param resumo      null quando nenhum anúncio passou nos eliminatórios
 * @param criterios   os critérios efetivos (já completados com o padrão), para a planilha registrar
 */
public record ResultadoCotacao(List<Avaliado> elegiveis, List<Descartado> descartados, List<Grupo> grupos,
        Resumo resumo, CriteriosCotacao criterios) {

    /** Pontos de 0 a 100 em cada dimensão, antes dos pesos — a tela mostra como barra. */
    public record Parciais(int preco, int entrega, int fornecedor, int marca) {
    }

    /**
     * @param segmento rótulo do segmento a que pertence, ou null
     * @param tier     classificação da marca: "A", "Sem marca", "Preferida" ou "Outra"
     * @param score    0–100, uma casa decimal
     */
    public record Avaliado(Anuncio anuncio, String segmento, String tier, Parciais parciais, double score) {
    }

    public record Descartado(Anuncio anuncio, String motivo) {
    }

    /** @param semSegmento é o bloco dos que não se encaixaram em nenhum segmento pedido */
    public record Grupo(String rotulo, boolean semSegmento, int qtd, double menorPreco, double precoMedio,
            List<Avaliado> top3, List<Avaliado> demais) {
    }

    /**
     * @param pesosEfetivos pesos normalizados em % (preco, entrega, fornecedor, marca)
     * @param semVolume     elegíveis cuja página não informou a quantidade vendida (a tela avisa)
     */
    public record Resumo(int analisados, int elegiveis, int descartados, double menorPreco, double maiorPreco,
            double mediana, double precoMedio, Map<String, Integer> pesosEfetivos, int semVolume,
            int internacionais) {
    }
}
