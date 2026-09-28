package br.com.rdamasio.helpagent.cotacao;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Component;

import br.com.rdamasio.helpagent.cotacao.ResultadoCotacao.Avaliado;
import br.com.rdamasio.helpagent.cotacao.ResultadoCotacao.Descartado;
import br.com.rdamasio.helpagent.cotacao.ResultadoCotacao.Grupo;
import br.com.rdamasio.helpagent.cotacao.ResultadoCotacao.Parciais;
import br.com.rdamasio.helpagent.cotacao.ResultadoCotacao.Resumo;

/**
 * Eliminatórios + score ponderado da cotação. Java puro, sem navegador: é testável com a amostra gravada e tem
 * paridade verificada com o piloto em Python ({@code MotorCotacaoParidadeTest}).
 *
 * <p>Dois estágios, nesta ordem:
 * <ol>
 *   <li><b>Eliminatórios</b> cortam o anúncio antes da pontuação — produto errado ou vendedor sem reputação não
 *       deve competir por preço. <b>A ordem importa</b>: o primeiro que bate vira o motivo exibido, e a sequência
 *       produto → logística → reputação faz o motivo refletir o filtro que a pessoa acabou de mexer.</li>
 *   <li><b>Score</b> 0–100 entre os que sobraram: preço, entrega, fornecedor e marca, pelos pesos normalizados.</li>
 * </ol>
 */
@Component
public class MotorCotacao {

    /** Marcas "tier A" de TI, usadas quando o usuário não informa as preferidas. Espaço no fim evita "hpe", "lgbt"... */
    static final List<String> MARCAS_TI = List.of("samsung", "kingston", "western digital", "wd ", "crucial",
            "sandisk", "seagate", "adata", "lexar", "patriot", "kioxia", "micron", "intel", "logitech", "dell", "hp ",
            "lenovo", "asus", "acer", "aoc", "lg ", "philips", "epson", "brother", "tp-link", "intelbras",
            "multilaser");

    /** Quantidade vendida → pontos de volume (primeira faixa que o anúncio alcança). */
    private static final int[] FAIXA_VOLUME = {10000, 5000, 1000, 500, 100, 25, 0};
    private static final double[] PONTOS_VOLUME = {1.00, 0.95, 0.85, 0.70, 0.50, 0.30, 0.0};

    private static final Locale PT_BR = Locale.forLanguageTag("pt-BR");

    /**
     * Nota presumida quando quem vende é a própria loja/fabricante e não há avaliação pública (e o critério
     * {@code aceitarLojaPropriaSemNota} está ligado). 4,5 = o mínimo padrão de Suprimentos: a loja entra no jogo
     * sem vantagem sobre um vendedor bem avaliado. Mudar este valor é decisão de Suprimentos.
     */
    static final double NOTA_PRESUMIDA_LOJA_PROPRIA = 4.5;

    public ResultadoCotacao avaliar(List<Anuncio> anuncios, CriteriosCotacao pedido) {
        CriteriosCotacao c = (pedido == null ? CriteriosCotacao.PADRAO : pedido).completar();
        List<String> deve = lista(c.deveConter()).stream().map(MotorCotacao::norm).toList();
        List<String> nao = lista(c.naoPodeConter()).stream().map(MotorCotacao::norm).toList();
        List<String> preferidas = lista(c.marcasPreferidas());
        List<String> segmentos = lista(c.segmentos());

        List<Anuncio> passaram = new ArrayList<>();
        List<Descartado> descartados = new ArrayList<>();
        for (Anuncio a : anuncios) {
            String motivo = motivoDescarte(a, c, deve, nao);
            if (motivo != null) descartados.add(new Descartado(a, motivo));
            else passaram.add(a);
        }
        if (passaram.isEmpty()) return new ResultadoCotacao(List.of(), descartados, List.of(), null, c);

        double soma = c.pesoPreco() + c.pesoEntrega() + c.pesoFornecedor() + c.pesoMarca();
        if (soma == 0) soma = 1;
        double wPreco = c.pesoPreco() / soma, wEntrega = c.pesoEntrega() / soma;
        double wForn = c.pesoFornecedor() / soma, wMarca = c.pesoMarca() / soma;

        // o preço compara dentro do segmento: SSD M.2 não concorre com SATA
        Map<String, Double> menorPorSegmento = new LinkedHashMap<>();
        List<String> segDe = new ArrayList<>();
        for (Anuncio a : passaram) {
            String seg = segmentos.isEmpty() ? null : segmentar(a.titulo(), segmentos);
            segDe.add(seg);
            menorPorSegmento.merge(String.valueOf(seg), a.preco(), Math::min);
        }

        List<Avaliado> elegiveis = new ArrayList<>();
        for (int i = 0; i < passaram.size(); i++) {
            Anuncio a = passaram.get(i);
            String seg = segDe.get(i);
            double sPreco = menorPorSegmento.get(String.valueOf(seg)) / a.preco();
            double sEntrega = (a.full() ? c.pontosFull() : a.freteGratis() ? c.pontosFreteGratis() : c.pontosSemFrete())
                    / 100;
            double nota = a.nota() != null ? a.nota() : NOTA_PRESUMIDA_LOJA_PROPRIA; // null só chega aqui se é loja própria
            double notaNorm = Math.max(0.0, nota - 4.0);
            double oficial = a.lojaOficial() ? 0.10 : 0;
            // sem volume observado, a nota carrega 100% da reputação em vez de 60%: o anúncio não é
            // penalizado por um dado que a página não deu
            double sForn = a.vendidos() == null
                    ? Math.min(1.0, notaNorm + oficial)
                    : Math.min(1.0, 0.60 * notaNorm + 0.40 * pontosVolume(a.vendidos()) + oficial);
            Marca marca = tierMarca(a.titulo(), preferidas);
            double sMarca = marca.pontos();
            var parciais = new Parciais(inteiro(sPreco * 100), inteiro(sEntrega * 100), inteiro(sForn * 100),
                    inteiro(sMarca * 100));
            double score = arred((wPreco * sPreco + wEntrega * sEntrega + wForn * sForn + wMarca * sMarca) * 100, 1);
            elegiveis.add(new Avaliado(a, seg, marca.tier(), parciais, score));
        }
        elegiveis.sort((x, y) -> Double.compare(y.score(), x.score())); // estável: empate mantém a ordem da busca

        List<Grupo> grupos = new ArrayList<>();
        List<String> rotulos = new ArrayList<>(segmentos);
        rotulos.add(null);
        for (String seg : segmentos.isEmpty() ? Arrays.asList((String) null) : rotulos) {
            List<Avaliado> itens = elegiveis.stream().filter(e -> Objects.equals(e.segmento(), seg)).toList();
            if (itens.isEmpty()) continue;
            String rotulo = seg != null ? seg : segmentos.isEmpty() ? "Todos os resultados" : "Sem segmento";
            grupos.add(new Grupo(rotulo, seg == null && !segmentos.isEmpty(), itens.size(),
                    itens.stream().mapToDouble(e -> e.anuncio().preco()).min().orElseThrow(),
                    arred(itens.stream().mapToDouble(e -> e.anuncio().preco()).average().orElseThrow(), 2),
                    itens.subList(0, Math.min(3, itens.size())), itens.subList(Math.min(3, itens.size()), itens.size())));
        }

        double[] precos = elegiveis.stream().mapToDouble(e -> e.anuncio().preco()).sorted().toArray();
        Map<String, Integer> pesos = new LinkedHashMap<>();
        pesos.put("preco", inteiro(wPreco * 100));
        pesos.put("entrega", inteiro(wEntrega * 100));
        pesos.put("fornecedor", inteiro(wForn * 100));
        pesos.put("marca", inteiro(wMarca * 100));
        var resumo = new Resumo(anuncios.size(), elegiveis.size(), descartados.size(), precos[0],
                precos[precos.length - 1], precos[precos.length / 2], arred(Arrays.stream(precos).average().orElseThrow(), 2),
                pesos, (int) elegiveis.stream().filter(e -> e.anuncio().vendidos() == null).count(),
                (int) elegiveis.stream().filter(e -> e.anuncio().internacional()).count());
        return new ResultadoCotacao(elegiveis, descartados, grupos, resumo, c);
    }

    /** Primeiro eliminatório que o anúncio não passa, ou null se passou em todos. */
    private static String motivoDescarte(Anuncio a, CriteriosCotacao c, List<String> deve, List<String> nao) {
        String t = norm(a.titulo());
        List<String> faltando = deve.stream().filter(p -> !t.contains(p)).toList();
        if (!faltando.isEmpty()) return "título não contém: " + String.join(", ", faltando);
        List<String> proibidas = nao.stream().filter(t::contains).toList();
        if (!proibidas.isEmpty()) return "título contém termo excluído: " + String.join(", ", proibidas);
        if (a.recondicionado() && !c.aceitaRecondicionado()) return "produto recondicionado";
        // logística e preço antes da reputação: são decisões de política de compra
        if ("nacional".equals(c.origem()) && a.internacional()) {
            return "envio internacional" + (a.pais().isBlank() ? "" : " (" + a.pais() + ")");
        }
        if ("internacional".equals(c.origem()) && !a.internacional()) return "envio nacional";
        if (c.exigirFull() && !a.full()) return "não é enviado pelo FULL";
        if (c.precoMax() != null && a.preco() > c.precoMax()) {
            return String.format(PT_BR, "acima do teto de R$ %.2f", c.precoMax());
        }
        if (a.nota() == null) {
            // a regra do piloto (vendedor sem avaliação sai) é para vendedor de marketplace; quando a própria loja
            // ou o fabricante vende, a reputação é a da loja (ver NOTA_PRESUMIDA_LOJA_PROPRIA)
            if (!(a.vendedorProprio() && c.aceitarLojaPropriaSemNota())) return "vendedor sem avaliação pública";
        } else if (a.nota() < c.notaMinima()) {
            return String.format(PT_BR, "nota %.1f abaixo do mínimo %.1f", a.nota(), c.notaMinima());
        }
        // volume só elimina quando foi observado: o ML às vezes serve um layout sem a quantidade vendida,
        // e ausência de dado não é prova de pouca venda. NÃO trate null como 0 — derruba a lista inteira.
        if (a.vendidos() != null && a.vendidos() < c.vendidosMinimo()) {
            return "menos de " + c.vendidosMinimo() + " unidades vendidas";
        }
        return null;
    }

    static double pontosVolume(int vendidos) {
        for (int i = 0; i < FAIXA_VOLUME.length; i++) if (vendidos >= FAIXA_VOLUME[i]) return PONTOS_VOLUME[i];
        return 0.0;
    }

    record Marca(double pontos, String tier) {
    }

    /** Marca preferida (ou tier A de TI, quando não há preferidas) vale 100 pontos; as demais, 35. */
    static Marca tierMarca(String titulo, List<String> preferidas) {
        String t = norm(titulo);
        if (!preferidas.isEmpty()) {
            return preferidas.stream().anyMatch(m -> t.contains(norm(m))) ? new Marca(1.0, "Preferida")
                    : new Marca(0.35, "Outra");
        }
        return MARCAS_TI.stream().anyMatch(t::contains) ? new Marca(1.0, "A") : new Marca(0.35, "Sem marca");
    }

    /** Rótulo do primeiro segmento cujo nome aparece no título, ou null. */
    static String segmentar(String titulo, List<String> segmentos) {
        String t = norm(titulo);
        return segmentos.stream().filter(s -> t.contains(norm(s))).findFirst().orElse(null);
    }

    /** Minúsculas sem acento: "Módulo" casa com "modulo". */
    static String norm(String s) {
        String n = Normalizer.normalize(s == null ? "" : s.toLowerCase(Locale.ROOT), Normalizer.Form.NFKD);
        return n.replaceAll("\\p{M}", "");
    }

    /** "M.2, SATA; NVMe" → [M.2, SATA, NVMe]. */
    static List<String> lista(String texto) {
        if (texto == null) return List.of();
        return Arrays.stream(texto.split("[,;]")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    /**
     * Arredonda como o {@code round()} do Python (empate vai para o par, sobre o valor binário exato).
     * {@code Math.round} arredonda o empate para cima; com ele, scores e parciais divergiriam do piloto.
     */
    static double arred(double v, int casas) {
        return new BigDecimal(v).setScale(casas, RoundingMode.HALF_EVEN).doubleValue();
    }

    private static int inteiro(double v) {
        return (int) arred(v, 0);
    }
}
