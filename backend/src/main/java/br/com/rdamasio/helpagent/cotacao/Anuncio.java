package br.com.rdamasio.helpagent.cotacao;

import java.util.Map;

/**
 * Um anúncio coletado numa loja — o contrato que toda {@link FonteCotacao} devolve (no piloto em Python era a
 * constante {@code CAMPOS} de coletor.py). Motor, tela e planilha só conhecem esta forma: loja nova não mexe neles.
 *
 * <p>{@code null} quer dizer "a página não informou", nunca zero. Em especial {@code vendidos}: o Mercado Livre
 * às vezes serve um layout sem a quantidade vendida, e tratar isso como 0 derruba a lista inteira no filtro de
 * volume mínimo (MANUTENCAO §7).
 *
 * @param nota        0–5; null = vendedor sem avaliação pública
 * @param precoDe     preço anterior riscado, quando há desconto
 * @param pais        país de origem quando {@code internacional}; "" quando nacional
 * @param patrocinado anúncio pago: a URL coletada é um link de rastreamento até {@link ResolvedorPatrocinados} trocá-la
 * @param fonte       nome da loja para exibir ("Kabum", "Mercado Livre"...)
 * @param vendedorProprio quem vende é a própria loja ou o fabricante (Dell, Pichau, KaBuM! fora do marketplace).
 *                    Sem nota pública, a reputação é presumida pela loja em vez de eliminar — ver MotorCotacao
 */
public record Anuncio(String titulo, Double preco, Double precoDe, Double nota, Integer vendidos, boolean full,
        boolean lojaOficial, boolean freteGratis, boolean recondicionado, boolean internacional, String pais,
        String vendedor, String url, boolean patrocinado, String fonte, boolean vendedorProprio) {

    public Anuncio comUrl(String novaUrl) {
        return new Anuncio(titulo, preco, precoDe, nota, vendidos, full, lojaOficial, freteGratis, recondicionado,
                internacional, pais, vendedor, novaUrl, patrocinado, fonte, vendedorProprio);
    }

    /** Converte o objeto que o JavaScript da página devolve (chaves em snake_case, como no piloto). */
    static Anuncio doJs(Map<?, ?> m) {
        return new Anuncio(texto(m.get("titulo")), numero(m.get("preco")), numero(m.get("preco_de")),
                numero(m.get("nota")), inteiro(m.get("vendidos")), sim(m.get("full")), sim(m.get("loja_oficial")),
                sim(m.get("frete_gratis")), sim(m.get("recondicionado")), sim(m.get("internacional")),
                texto(m.get("pais")), texto(m.get("vendedor")), texto(m.get("url")), sim(m.get("patrocinado")),
                texto(m.get("fonte")), sim(m.get("vendedor_proprio")));
    }

    private static String texto(Object o) {
        return o == null ? "" : o.toString().strip();
    }

    private static Double numero(Object o) {
        return o instanceof Number n ? n.doubleValue() : null;
    }

    private static Integer inteiro(Object o) {
        return o instanceof Number n ? n.intValue() : null;
    }

    private static boolean sim(Object o) {
        return Boolean.TRUE.equals(o);
    }
}
