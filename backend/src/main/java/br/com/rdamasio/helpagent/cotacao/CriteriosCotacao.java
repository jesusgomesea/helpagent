package br.com.rdamasio.helpagent.cotacao;

/**
 * Parâmetros da cotação que o usuário ajusta na tela. Campo ausente (null) cai no {@link #PADRAO}.
 *
 * <p><b>Os valores padrão são política do departamento de Suprimentos, não decisão técnica</b> (HANDOFF §11):
 * qualquer mudança aqui passa por eles. Foram calibrados olhando itens de TI.
 *
 * @param pesoPreco         pesos do score; são normalizados pela soma dos quatro, não precisam somar 100
 * @param vendidosMinimo    eliminatório aplicado só quando a página informou a quantidade vendida
 * @param origem            "qualquer" | "nacional" | "internacional"
 * @param precoMax          teto de preço; null ou 0 = sem teto
 * @param deveConter        palavras obrigatórias no título, separadas por vírgula ou ponto e vírgula
 * @param naoPodeConter     palavras proibidas no título
 * @param pontosFull        pontuação de entrega (0–100) de quem é enviado pelo FULL
 * @param marcasPreferidas  vazio = usa a lista de marcas de TI do {@link MotorCotacao}
 * @param segmentos         ex.: "M.2, SATA" — um top 3 por segmento, com o preço comparado dentro dele
 * @param aceitarLojaPropriaSemNota quando a própria loja ou o fabricante vende (Dell, Pichau, KaBuM!), a falta de
 *                          nota pública não elimina e a reputação é presumida. Regra acrescentada ao trazer lojas
 *                          além do ML (28/09/2026) — sem ela quase todo produto da Dell e da Pichau seria descartado
 */
public record CriteriosCotacao(
        Double pesoPreco, Double pesoEntrega, Double pesoFornecedor, Double pesoMarca,
        Double notaMinima, Integer vendidosMinimo, Boolean aceitaRecondicionado, Boolean exigirFull,
        String origem, Double precoMax, String deveConter, String naoPodeConter,
        Double pontosFull, Double pontosFreteGratis, Double pontosSemFrete,
        String marcasPreferidas, String segmentos, Boolean aceitarLojaPropriaSemNota) {

    public static final CriteriosCotacao PADRAO = new CriteriosCotacao(
            40.0, 20.0, 25.0, 15.0,
            4.5, 100, false, false,
            "qualquer", null, "", "",
            100.0, 60.0, 20.0,
            "", "", true);

    /** Este critério com os campos vazios preenchidos pelo padrão. */
    public CriteriosCotacao completar() {
        CriteriosCotacao p = PADRAO;
        return new CriteriosCotacao(
                ou(pesoPreco, p.pesoPreco), ou(pesoEntrega, p.pesoEntrega),
                ou(pesoFornecedor, p.pesoFornecedor), ou(pesoMarca, p.pesoMarca),
                ou(notaMinima, p.notaMinima), ou(vendidosMinimo, p.vendidosMinimo),
                ou(aceitaRecondicionado, p.aceitaRecondicionado), ou(exigirFull, p.exigirFull),
                origem == null || origem.isBlank() ? p.origem : origem,
                precoMax == null || precoMax <= 0 ? null : precoMax,
                ou(deveConter, ""), ou(naoPodeConter, ""),
                ou(pontosFull, p.pontosFull), ou(pontosFreteGratis, p.pontosFreteGratis),
                ou(pontosSemFrete, p.pontosSemFrete),
                ou(marcasPreferidas, ""), ou(segmentos, ""), ou(aceitarLojaPropriaSemNota, p.aceitarLojaPropriaSemNota));
    }

    private static <T> T ou(T valor, T padrao) {
        return valor != null ? valor : padrao;
    }
}
