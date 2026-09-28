package br.com.rdamasio.helpagent.cotacao;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitForSelectorState;
import com.microsoft.playwright.options.WaitUntilState;

import br.com.rdamasio.helpagent.config.HelpAgentProperties;

/**
 * Mede onde vai o tempo da cotação, loja por loja, reproduzindo o que o {@link ColetorCotacao} faz (uma aba por
 * loja, todas as navegações disparadas antes de ler). Para cada loja: início da resposta, HTML pronto
 * (DOMContentLoaded), página carregada (load), lista encontrada, extração, e as requisições por tipo.
 * Roda duas vezes: como a coleta faz hoje e com disparo imediato das navegações, para medir o ganho. Bloquear
 * imagem/fonte por {@code route} foi medido em 28/09/2026 e piorou (cada requisição passa pelo Java) — o código
 * ficou aqui para repetir a medição. Só diagnóstico: não altera a coleta.
 *
 * <pre>./mvnw test -Dtest=DiagnosticoTempoManualTest -Dcotacao.diagnostico=true [-Dcotacao.termo="ssd 256gb"]</pre>
 */
@EnabledIfSystemProperty(named = "cotacao.diagnostico", matches = "true")
class DiagnosticoTempoManualTest {

    private static final List<FonteComScript> LOJAS = List.of(new FonteKabum(), new FontePichau(), new FonteTerabyte(),
            new FonteAmazon(), new FonteMercadoLivre(), new FonteDell(), new FonteLenovo());
    private static final Set<String> PESADOS = Set.of("image", "font", "media");

    /** Padrões bloqueados no modo 2: imagens, fontes, vídeo e rastreadores/anúncios conhecidos. */
    static final List<String> BLOQUEIO_CDP = List.of("*.jpg*", "*.jpeg*", "*.png*", "*.gif*", "*.webp*", "*.avif*",
            "*.svg*", "*.ico*", "*.woff*", "*.woff2*", "*.ttf*", "*.otf*", "*.mp4*", "*.webm*",
            "*google-analytics.com*", "*googletagmanager.com*", "*doubleclick.net*", "*googlesyndication.com*",
            "*facebook.net*", "*facebook.com/tr*", "*hotjar.com*", "*clarity.ms*", "*tiktok.com*", "*criteo*",
            "*taboola*", "*outbrain*", "*nr-data.net*", "*newrelic.com*", "*bing.com/bat*", "*pinimg.com*");

    /** Tempos de uma loja, em ms desde o início da onda. -1 = não aconteceu. */
    private static final class Medida {
        final String loja;
        final AtomicLong dcl = new AtomicLong(-1), load = new AtomicLong(-1);
        long commit = -1, lista = -1, extracao = -1, itens;
        boolean semLista;
        final Map<String, Integer> reqs = new ConcurrentHashMap<>();
        int bloqueadas;

        Medida(String loja) {
            this.loja = loja;
        }
    }

    @Test
    void medir() {
        String termo = System.getProperty("cotacao.termo", "ssd 256gb");
        var cfg = new HelpAgentProperties.Cotacao("chrome", Path.of("dados/navegador"), 3, Duration.ofMinutes(30), false);
        var coletor = new ColetorCotacao(List.of(), new ResolvedorPatrocinados(),
                new HelpAgentProperties(null, null, null, null, cfg, null, List.of()));
        // modo 0 = como a coleta faz hoje (navigate COMMIT, uma aba de cada vez)
        // modo 1 = disparo imediato: a aba recebe location.href e o Java segue sem esperar a resposta do site
        // modo 2 = disparo imediato + o próprio Chrome bloqueia imagem/fonte/rastreador (CDP, sem passar pelo Java)
        int[] modos = java.util.Arrays.stream(System.getProperty("cotacao.modos", "0,1").split(","))
                .mapToInt(x -> Integer.parseInt(x.strip())).toArray();
        for (int modo : modos) {
            boolean bloquear = false;
            long tAbrir = System.nanoTime();
            long[] abrirMs = {0};
            List<Medida> medidas = coletor.usarContexto(ctx -> {
                abrirMs[0] = (System.nanoTime() - tAbrir) / 1_000_000;
                List<Medida> ms = new ArrayList<>();
                List<Page> abas = new ArrayList<>();
                long t0 = System.nanoTime();
                for (FonteComScript f : LOJAS) {
                    Medida m = new Medida(f.nome());
                    ms.add(m);
                    Page aba = ctx.newPage();
                    if (bloquear) {
                        aba.route("**/*", rota -> {
                            if (PESADOS.contains(rota.request().resourceType())) {
                                m.bloqueadas++;
                                rota.abort();
                            } else {
                                rota.resume();
                            }
                        });
                    }
                    // contar requisições passa cada evento pelo Java e distorce a medida: só com -Dcotacao.contar=true
                    if (Boolean.getBoolean("cotacao.contar")) aba.onRequest(r -> m.reqs.merge(r.resourceType(), 1, Integer::sum));
                    if (modo == 2) {
                        var cdp = ctx.newCDPSession(aba);
                        cdp.send("Network.enable");
                        var params = new com.google.gson.JsonObject();
                        var urls = new com.google.gson.JsonArray();
                        BLOQUEIO_CDP.forEach(urls::add);
                        params.add("urls", urls);
                        cdp.send("Network.setBlockedURLs", params);
                    }
                    aba.onDOMContentLoaded(p -> m.dcl.compareAndSet(-1, ms(t0)));
                    aba.onLoad(p -> m.load.compareAndSet(-1, ms(t0)));
                    try {
                        if (modo == 0) {
                            aba.navigate(f.urls(termo, 1).getFirst(),
                                    new Page.NavigateOptions().setWaitUntil(WaitUntilState.COMMIT).setTimeout(45_000));
                        } else {
                            aba.evaluate("u => { location.href = u; }", f.urls(termo, 1).getFirst());
                        }
                        m.commit = ms(t0);
                    } catch (PlaywrightException e) {
                        m.commit = -2;
                    }
                    abas.add(aba);
                }
                for (int i = 0; i < abas.size(); i++) {
                    Page aba = abas.get(i);
                    Medida m = ms.get(i);
                    try {
                        // waitUntil COMMIT: o padrão do waitForURL é esperar o "load" da página, justamente o que se quer evitar
                        if (modo >= 1) aba.waitForURL(u -> !u.startsWith("about:"), new Page.WaitForURLOptions()
                                .setWaitUntil(WaitUntilState.COMMIT).setTimeout(45_000));
                        aba.waitForLoadState(LoadState.DOMCONTENTLOADED, new Page.WaitForLoadStateOptions().setTimeout(45_000));
                        aba.waitForSelector(seletor(LOJAS.get(i)), new Page.WaitForSelectorOptions()
                                .setState(WaitForSelectorState.ATTACHED).setTimeout(30_000));
                        m.lista = ms(t0);
                    } catch (PlaywrightException e) {
                        m.semLista = true;
                        m.lista = ms(t0);
                    }
                    long te = System.nanoTime();
                    m.itens = m.semLista ? 0 : LOJAS.get(i).extrair(aba).size();
                    m.extracao = (System.nanoTime() - te) / 1_000_000;
                    aba.close();
                }
                return ms;
            });
            long totalMs = (System.nanoTime() - tAbrir) / 1_000_000;
            System.out.printf("%n==== '%s' · %s · abrir Chrome %d ms · total com fechar %d ms%n", termo,
                    switch (modo) {
                        case 0 -> "como hoje (navigate COMMIT)";
                        case 1 -> "disparo imediato (location.href)";
                        default -> "disparo imediato + bloqueio no Chrome (CDP)";
                    }, abrirMs[0], totalMs);
            System.out.printf("  %-14s %7s %7s %7s %9s %7s %5s  %s%n", "loja", "commit", "html", "load", "lista", "extr", "itens", "requisições");
            for (Medida m : medidas) {
                System.out.printf("  %-14s %7d %7d %7d %9s %7d %5d  %s%s%n", m.loja, m.commit, m.dcl.get(), m.load.get(),
                        m.lista + (m.semLista ? "*" : ""), m.extracao, m.itens, new TreeMap<>(m.reqs),
                        bloquear ? " (bloqueadas " + m.bloqueadas + ")" : "");
            }
            System.out.println("  (ms desde o disparo da 1ª navegação; lista* = desistiu no limite de 30 s; load -1 = não terminou)");
        }
    }

    private static long ms(long t0) {
        return (System.nanoTime() - t0) / 1_000_000;
    }

    /** O seletor de "lista pronta" é privado na fonte; aqui repetimos os mesmos das classes. */
    private static String seletor(FonteComScript f) {
        return switch (f.id()) {
            case "kabum" -> "script#__NEXT_DATA__";
            case "pichau" -> "a[href]";
            case "terabyte" -> ".product-item";
            case "amazon" -> "div[data-component-type='s-search-result']";
            case "mercadolivre" -> "li.ui-search-layout__item";
            case "dell" -> "[data-product-detail]";
            case "lenovo" -> ".product_item[data-product-code]";
            default -> "body";
        };
    }
}
