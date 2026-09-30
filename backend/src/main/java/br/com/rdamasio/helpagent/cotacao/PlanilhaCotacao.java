package br.com.rdamasio.helpagent.cotacao;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.poi.common.usermodel.HyperlinkType;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFHyperlink;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import br.com.rdamasio.helpagent.config.HelpAgentProperties;
import br.com.rdamasio.helpagent.cotacao.ResultadoCotacao.Avaliado;
import br.com.rdamasio.helpagent.cotacao.ResultadoCotacao.Descartado;
import br.com.rdamasio.helpagent.cotacao.ResultadoCotacao.Grupo;

/**
 * Planilha .xlsx da cotação, no formato acordado com Suprimentos no piloto (planilha.py, com a coluna Loja
 * acrescentada quando a cotação passou a consultar várias lojas): quatro abas —
 * Resumo (top 3 por grupo), Critérios (o que estava aplicado), Análise (todos os elegíveis com as parciais)
 * e Descartados (com o motivo e o link, para o comprador conferir por que o item saiu).
 */
@Component
public class PlanilhaCotacao {

    private static final String AZUL = "1F3864", AZUL_2 = "2E5C8A", CINZA = "F2F2F2", VERDE = "E2EFDA",
            AMARELO = "FFF2CC", LARANJA = "FCE4D6", MARROM = "8B4513", BRANCO = "FFFFFF", LINHA = "D9D9D9";
    private static final String REAL = "\"R$\" #,##0.00";
    private static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final HelpAgentProperties props;

    public PlanilhaCotacao(HelpAgentProperties props) {
        this.props = props;
    }

    public byte[] gerar(CotacaoService.Guardada g) {
        String hoje = g.coletadoEm().atZone(props.fuso()).format(DATA);
        try (XSSFWorkbook wb = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            Estilos e = new Estilos(wb);
            resumo(wb.createSheet("Resumo"), e, g, hoje);
            criterios(wb.createSheet("Critérios"), e, g.resultado());
            analise(wb.createSheet("Análise"), e, g.resultado().elegiveis());
            descartados(wb.createSheet("Descartados"), e, g.resultado().descartados());
            wb.write(out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    // ------------------------------------------------------------------------------------------------ Resumo
    private void resumo(XSSFSheet s, Estilos e, CotacaoService.Guardada g, String hoje) {
        s.setDisplayGridlines(false);
        var r = g.resultado();
        titulo(s, e, 0, "COTAÇÃO  -  " + g.termo().toUpperCase(), AZUL, 8);
        s.getRow(0).setHeightInPoints(24);

        var res = r.resumo();
        Object[][] meta = {
                {"Termo pesquisado", g.termo()},
                {"Lojas consultadas", g.porFonte().isEmpty() ? "-" : String.join(", ", g.porFonte().keySet())},
                {"Data da coleta", hoje},
                {"Anúncios analisados", res == null ? r.descartados().size() : res.analisados()},
                {"Elegíveis após eliminatórios", r.elegiveis().size()}, {"Descartados", r.descartados().size()},
                {"Menor preço elegível", res == null ? null : res.menorPreco()},
                {"Preço médio elegível", res == null ? null : res.precoMedio()}};
        for (int i = 0; i < meta.length; i++) {
            Row row = s.createRow(2 + i);
            set(row, 0, meta[i][0], e.get(10, true, false, null, null, null));
            set(row, 2, meta[i][1], e.get(10, false, false, null, null, ((String) meta[i][0]).startsWith("Menor")
                    || ((String) meta[i][0]).startsWith("Preço") ? REAL : null));
        }

        int linha = 12;
        for (Grupo grupo : r.grupos()) {
            titulo(s, e, linha, "TOP 3  -  %s   (%d elegíveis)".formatted(grupo.rotulo(), grupo.qtd()), AZUL_2, 8);
            linha++;
            cabecalho(s, e, linha, List.of("Posição", "Produto", "Loja", "Preço", "Score", "Nota", "Vendidos", "Entrega", "Link"));
            linha++;
            int pos = 1;
            for (Avaliado a : grupo.top3()) {
                Anuncio an = a.anuncio();
                String fundo = pos == 1 ? VERDE : BRANCO;
                boolean negrito = pos == 1;
                Row row = s.createRow(linha);
                set(row, 0, "#" + pos, e.get(11, true, false, null, fundo, null, true));
                set(row, 1, an.titulo(), e.get(10, negrito, false, null, fundo, null, true));
                set(row, 2, an.fonte(), e.get(10, negrito, false, null, fundo, null, true));
                set(row, 3, an.preco(), e.get(10, negrito, false, null, fundo, REAL, true));
                set(row, 4, a.score() / 100, e.get(10, negrito, false, null, fundo, "0.0%", true));
                set(row, 5, an.nota(), e.get(10, negrito, false, null, fundo, "0.0", true));
                set(row, 6, an.vendidos(), e.get(10, negrito, false, null, fundo, null, true));
                set(row, 7, entrega(an), e.get(10, negrito, false, null, fundo, null, true));
                link(row, 8, an, e.link(10, fundo), e.get(10, negrito, false, null, fundo, null, true));
                linha++;
                pos++;
            }
            Row row = s.createRow(linha);
            var italico = e.get(9, false, true, null, null, null);
            var italicoReal = e.get(9, false, true, null, null, REAL);
            set(row, 1, "Menor preço do grupo", italico);
            set(row, 3, grupo.menorPreco(), italicoReal);
            set(row, 5, "Preço médio", italico);
            set(row, 6, grupo.precoMedio(), italicoReal);
            linha += 3;
        }

        Row aviso = s.createRow(linha);
        set(aviso, 0, "Preços coletados em " + hoje + ". As lojas mudam o preço conforme a conta logada, o CEP e as "
                + "promoções do dia; confirme na loja antes de fechar a compra.",
                e.get(9, false, true, "C00000", null, null));
        s.addMergedRegion(new CellRangeAddress(linha, linha, 0, 8));
        int[] larguras = {10, 52, 13, 13, 10, 8, 11, 15, 16};
        for (int i = 0; i < larguras.length; i++) s.setColumnWidth(i, larguras[i] * 256);
    }

    // --------------------------------------------------------------------------------------------- Critérios
    private void criterios(XSSFSheet s, Estilos e, ResultadoCotacao r) {
        s.setDisplayGridlines(false);
        CriteriosCotacao c = r.criterios();
        titulo(s, e, 0, "CRITÉRIOS USADOS NESTA COTAÇÃO", AZUL, 2);
        set(s.createRow(1), 0, "Estes são os valores aplicados na página no momento da cotação. "
                + "Mudar o critério muda o ranking — refaça a cotação.", e.get(9, false, true, null, null, null));

        Map<String, Integer> pe = r.resumo() == null ? null : r.resumo().pesosEfetivos();
        List<Object[]> blocos = new ArrayList<>();
        blocos.add(new Object[] {"PESOS EFETIVOS", List.of(
                par("Preço", pe == null ? "-" : pe.get("preco") + "%"),
                par("Entrega", pe == null ? "-" : pe.get("entrega") + "%"),
                par("Fornecedor", pe == null ? "-" : pe.get("fornecedor") + "%"),
                par("Marca", pe == null ? "-" : pe.get("marca") + "%"))});
        blocos.add(new Object[] {"ELIMINATÓRIOS", List.of(
                par("Nota mínima do vendedor", c.notaMinima()),
                par("Loja própria sem nota", c.aceitarLojaPropriaSemNota() ? "aceita (reputação presumida)" : "descarta"),
                par("Mínimo de unidades vendidas", c.vendidosMinimo()),
                par("Aceita recondicionado", c.aceitaRecondicionado() ? "sim" : "não"),
                par("Exige envio FULL", c.exigirFull() ? "sim" : "não"),
                par("Origem do envio", switch (c.origem()) {
                    case "nacional" -> "só nacional";
                    case "internacional" -> "só internacional";
                    default -> "qualquer origem";
                }),
                par("Teto de preço", c.precoMax() == null ? "sem teto" : c.precoMax()),
                par("Título deve conter", c.deveConter().isBlank() ? "-" : c.deveConter()),
                par("Título não pode conter", c.naoPodeConter().isBlank() ? "-" : c.naoPodeConter()))});
        blocos.add(new Object[] {"PONTUAÇÃO DE ENTREGA", List.of(
                par("Enviado pelo FULL", pts(c.pontosFull())),
                par("Frete grátis", pts(c.pontosFreteGratis())),
                par("Sem frete grátis", pts(c.pontosSemFrete())))});
        blocos.add(new Object[] {"OUTROS", List.of(
                par("Marcas preferidas", c.marcasPreferidas().isBlank() ? "lista padrão de TI" : c.marcasPreferidas()),
                par("Segmentos", c.segmentos().isBlank() ? "sem segmentação" : c.segmentos()))});

        int i = 3;
        var valor = e.get(10, true, false, "0000FF", AMARELO, null);
        for (Object[] bloco : blocos) {
            set(s.createRow(i++), 0, bloco[0], e.get(10, true, false, null, null, null));
            @SuppressWarnings("unchecked")
            List<Object[]> itens = (List<Object[]>) bloco[1];
            for (Object[] kv : itens) {
                Row row = s.createRow(i++);
                set(row, 0, kv[0], e.get(10, false, false, null, null, null));
                set(row, 1, kv[1], valor);
            }
            i++;
        }
        s.setColumnWidth(0, 34 * 256);
        s.setColumnWidth(1, 40 * 256);
    }

    // ---------------------------------------------------------------------------------------------- Análise
    private void analise(XSSFSheet s, Estilos e, List<Avaliado> elegiveis) {
        s.setDisplayGridlines(false);
        cabecalho(s, e, 0, List.of("Produto", "Loja", "Segmento", "Preço", "Preço de", "Nota", "Vendidos", "FULL/Prime",
                "Origem", "Loja oficial", "Frete grátis", "Marca", "Vendedor", "Link", "Pts preço", "Pts entrega",
                "Pts fornecedor", "Pts marca", "SCORE"));
        int[] larguras = {52, 13, 14, 12, 12, 7, 10, 9, 14, 11, 11, 12, 20, 14, 10, 11, 12, 10, 10};
        for (int i = 0; i < larguras.length; i++) s.setColumnWidth(i, larguras[i] * 256);

        int linha = 1;
        for (Avaliado a : elegiveis) {
            Anuncio an = a.anuncio();
            String fundo = linha % 2 == 1 ? CINZA : null; // zebra igual ao piloto (linhas pares do Excel)
            Row row = s.createRow(linha);
            var p = a.parciais();
            Object[] vals = {an.titulo(), an.fonte(), a.segmento() == null ? "-" : a.segmento(), an.preco(), an.precoDe(),
                    an.nota(), an.vendidos(), simNao(an.full()), origem(an), simNao(an.lojaOficial()),
                    simNao(an.freteGratis()), a.tier(), an.vendedor(), null, p.preco() / 100.0, p.entrega() / 100.0,
                    p.fornecedor() / 100.0, p.marca() / 100.0, a.score() / 100};
            for (int col = 0; col < vals.length; col++) {
                if (col == 13) continue; // link, abaixo
                String fmt = switch (col) {
                    case 3, 4 -> REAL;
                    case 5 -> "0.0";
                    case 14, 15, 16, 17 -> "0%";
                    case 18 -> "0.0%";
                    default -> null;
                };
                boolean negrito = col == 18 || (col == 8 && an.internacional());
                String cor = col == 8 && an.internacional() ? "C00000" : null;
                set(row, col, vals[col], e.get(9, negrito, false, cor, fundo, fmt, true));
            }
            link(row, 13, an, e.link(9, fundo), e.get(9, false, false, null, fundo, null, true));
            linha++;
        }
        s.createFreezePane(2, 1);
        if (!elegiveis.isEmpty()) s.setAutoFilter(new CellRangeAddress(0, elegiveis.size(), 0, 18));
    }

    // ------------------------------------------------------------------------------------------ Descartados
    private void descartados(XSSFSheet s, Estilos e, List<Descartado> descartados) {
        s.setDisplayGridlines(false);
        titulo(s, e, 0, "ANÚNCIOS DESCARTADOS PELOS ELIMINATÓRIOS", MARROM, 3);
        cabecalho(s, e, 2, List.of("Produto", "Loja", "Preço", "Origem", "Motivo do descarte", "Vendedor", "Link do anúncio"));
        int[] larguras = {52, 13, 13, 14, 42, 18, 16};
        for (int i = 0; i < larguras.length; i++) s.setColumnWidth(i, larguras[i] * 256);

        List<Descartado> ordenados = descartados.stream().sorted(Comparator.comparing(Descartado::motivo)).toList();
        int linha = 3;
        var normal = e.get(9, false, false, null, LARANJA, null, true);
        for (Descartado d : ordenados) {
            Anuncio an = d.anuncio();
            Row row = s.createRow(linha++);
            set(row, 0, an.titulo(), normal);
            set(row, 1, an.fonte(), normal);
            set(row, 2, an.preco(), e.get(9, false, false, null, LARANJA, REAL, true));
            set(row, 3, origem(an), normal);
            set(row, 4, d.motivo(), normal);
            set(row, 5, an.vendedor(), normal);
            // o link importa aqui: o comprador quer conferir por que o item saiu
            link(row, 6, an, e.link(9, LARANJA), e.get(9, false, true, "7A8693", LARANJA, null, true));
        }
        if (!ordenados.isEmpty()) s.setAutoFilter(new CellRangeAddress(2, 2 + ordenados.size(), 0, 6));
    }

    // ---------------------------------------------------------------------------------------------- helpers
    private static Object[] par(String k, Object v) {
        return new Object[] {k, v};
    }

    private static String pts(double v) {
        return (v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v)) + " pts";
    }

    /** "FULL" é a entrega expressa do Mercado Livre. */
    private static String entrega(Anuncio a) {
        if (a.full()) return "FULL";
        return a.freteGratis() ? "Frete grátis" : "Envio comum";
    }

    private static String simNao(boolean b) {
        return b ? "Sim" : "Não";
    }

    private static String origem(Anuncio a) {
        return a.internacional() ? (a.pais().isBlank() ? "Internacional" : a.pais()) : "Nacional";
    }

    private static void titulo(XSSFSheet s, Estilos e, int linha, String texto, String cor, int ultimaColuna) {
        Row row = s.getRow(linha) != null ? s.getRow(linha) : s.createRow(linha);
        set(row, 0, texto, e.get(12, true, false, BRANCO, cor, null));
        s.addMergedRegion(new CellRangeAddress(linha, linha, 0, ultimaColuna));
    }

    private static void cabecalho(XSSFSheet s, Estilos e, int linha, List<String> nomes) {
        Row row = s.createRow(linha);
        row.setHeightInPoints(28);
        XSSFCellStyle st = e.cabecalho();
        for (int i = 0; i < nomes.size(); i++) set(row, i, nomes.get(i), st);
    }

    /** Célula de link: "abrir anúncio" (ou "(ad)" se patrocinado) clicável; "sem link" quando não há URL. */
    private static void link(Row row, int col, Anuncio a, XSSFCellStyle estiloLink, XSSFCellStyle semLink) {
        Cell c = row.createCell(col);
        if (a.url() == null || a.url().isBlank()) {
            c.setCellValue("sem link");
            c.setCellStyle(semLink);
            return;
        }
        c.setCellValue(a.patrocinado() ? "abrir anúncio (ad)" : "abrir anúncio");
        XSSFHyperlink h = ((XSSFWorkbook) row.getSheet().getWorkbook()).getCreationHelper()
                .createHyperlink(HyperlinkType.URL);
        h.setAddress(a.url());
        c.setHyperlink(h);
        c.setCellStyle(estiloLink);
    }

    private static void set(Row row, int col, Object v, XSSFCellStyle estilo) {
        Cell c = row.createCell(col);
        switch (v) {
            case null -> { }
            case Number n -> c.setCellValue(n.doubleValue());
            default -> c.setCellValue(v.toString());
        }
        c.setCellStyle(estilo);
    }

    /**
     * Cache de estilos: o Excel limita os estilos distintos por arquivo, e criar um por célula estoura numa
     * cotação de 3 páginas. A chave é a combinação de atributos.
     */
    private static final class Estilos {
        private final XSSFWorkbook wb;
        private final Map<String, XSSFCellStyle> cache = new HashMap<>();

        Estilos(XSSFWorkbook wb) {
            this.wb = wb;
        }

        XSSFCellStyle get(int tamanho, boolean negrito, boolean italico, String cor, String fundo, String formato) {
            return get(tamanho, negrito, italico, cor, fundo, formato, false);
        }

        XSSFCellStyle get(int tamanho, boolean negrito, boolean italico, String cor, String fundo, String formato,
                boolean bordaInferior) {
            String chave = tamanho + "|" + negrito + "|" + italico + "|" + cor + "|" + fundo + "|" + formato + "|"
                    + bordaInferior;
            return cache.computeIfAbsent(chave, k -> {
                XSSFCellStyle st = wb.createCellStyle();
                XSSFFont f = wb.createFont();
                f.setFontName("Arial");
                f.setFontHeightInPoints((short) tamanho);
                f.setBold(negrito);
                f.setItalic(italico);
                if (cor != null) f.setColor(rgb(cor));
                st.setFont(f);
                if (fundo != null) {
                    st.setFillForegroundColor(rgb(fundo));
                    st.setFillPattern(FillPatternType.SOLID_FOREGROUND);
                }
                if (formato != null) st.setDataFormat(wb.createDataFormat().getFormat(formato));
                if (bordaInferior) {
                    st.setBorderBottom(BorderStyle.THIN);
                    st.setBottomBorderColor(rgb(LINHA));
                }
                return st;
            });
        }

        // cabecalho() e link() derivam de get(); não usam computeIfAbsent porque get() também grava no mapa
        // e o HashMap recusa alteração durante o cálculo (ConcurrentModificationException)
        XSSFCellStyle cabecalho() {
            XSSFCellStyle st = cache.get("cabecalho");
            if (st == null) {
                st = get(9, true, false, BRANCO, AZUL, null).copy();
                st.setAlignment(HorizontalAlignment.CENTER);
                st.setVerticalAlignment(VerticalAlignment.CENTER);
                st.setWrapText(true);
                cache.put("cabecalho", st);
            }
            return st;
        }

        XSSFCellStyle link(int tamanho, String fundo) {
            String chave = "link|" + tamanho + "|" + fundo;
            XSSFCellStyle st = cache.get(chave);
            if (st == null) {
                st = get(tamanho, false, false, null, fundo, null, true).copy();
                XSSFFont f = wb.createFont();
                f.setFontName("Arial");
                f.setFontHeightInPoints((short) tamanho);
                f.setColor(rgb("0563C1"));
                f.setUnderline(XSSFFont.U_SINGLE);
                st.setFont(f);
                cache.put(chave, st);
            }
            return st;
        }

        private static XSSFColor rgb(String hex) {
            return new XSSFColor(new byte[] {(byte) Integer.parseInt(hex.substring(0, 2), 16),
                    (byte) Integer.parseInt(hex.substring(2, 4), 16), (byte) Integer.parseInt(hex.substring(4, 6), 16)},
                    null);
        }
    }
}
