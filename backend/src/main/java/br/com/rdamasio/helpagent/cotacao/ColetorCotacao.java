package br.com.rdamasio.helpagent.cotacao;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.WaitUntilState;

import br.com.rdamasio.helpagent.config.HelpAgentProperties;

/**
 * Abre o Chrome, busca o termo em cada {@link FonteCotacao} pedida e devolve os anúncios limpos (patrocinados
 * resolvidos, sem duplicados). É a única parte da cotação que depende de navegador — motor e planilha são Java puro.
 *
 * <p><b>Lojas em paralelo:</b> abre uma aba por página de loja e dispara todas as navegações antes de ler qualquer
 * uma ({@link WaitUntilState#COMMIT}: só espera a resposta começar). O Chrome carrega tudo ao mesmo tempo e o
 * total fica perto da loja mais lenta, não da soma. Em ondas de {@link #ABAS_POR_ONDA} para não pesar a máquina.
 *
 * <p>Por que Chrome de verdade, com janela, e não HTTP ou headless: o Mercado Livre bloqueia os dois (o piloto
 * testou seis abordagens, tabela em legacy/cotacao-suprimentos/HANDOFF.md §7.1). Consequências que valem para quem mantém:
 * <ul>
 *   <li>usa o Chrome instalado ({@code canal}), nunca baixa o Chromium do Playwright;</li>
 *   <li>a janela existe, só que fora da tela ({@code --window-position=-3000,-3000});</li>
 *   <li>o backend precisa rodar com um usuário logado — como Serviço do Windows (sessão 0) o Chrome não sobe;</li>
 *   <li>o perfil persistente guarda cookies; se um site pedir verificação, resolve-se à mão uma vez com
 *       {@code janela-visivel: true} e as próximas passam.</li>
 * </ul>
 *
 * <p>Uma cotação por vez (trava): o Chrome sobe e desce a cada uma, e os objetos do Playwright não podem ser
 * usados por duas threads. Quem chega durante uma cotação espera — {@link #ocupado()} deixa a tela avisar.
 */
@Component
public class ColetorCotacao {

    private static final Logger log = LoggerFactory.getLogger(ColetorCotacao.class);

    /** Abas abertas ao mesmo tempo. 6 lojas × 1 página cabem numa onda só. */
    static final int ABAS_POR_ONDA = 8;

    /**
     * @param porFonte anúncios aproveitados de cada loja, na ordem pedida (0 = não devolveu nada) — a tela mostra
     */
    public record Coleta(List<Anuncio> anuncios, List<String> avisos, Map<String, Integer> porFonte) {
    }

    /** Uma página de uma loja a abrir. */
    private record Alvo(FonteCotacao fonte, String url) {
    }

    private final Map<String, FonteCotacao> fontes;
    private final ResolvedorPatrocinados patrocinados;
    private final HelpAgentProperties.Cotacao cfg;
    private final ReentrantLock trava = new ReentrantLock(true);

    public ColetorCotacao(List<FonteCotacao> fontes, ResolvedorPatrocinados patrocinados, HelpAgentProperties props) {
        this.fontes = fontes.stream().collect(Collectors.toMap(FonteCotacao::id, Function.identity(),
                (a, b) -> a, LinkedHashMap::new));
        this.patrocinados = patrocinados;
        this.cfg = props.cotacao();
    }

    /** Lojas cadastradas (componentes {@link FonteCotacao}), na ordem do {@code @Order} de cada uma (por grupo). */
    public List<FonteCotacao> fontes() {
        return List.copyOf(fontes.values());
    }

    /** Há uma cotação em andamento (a próxima vai esperar). */
    public boolean ocupado() {
        return trava.isLocked();
    }

    public Coleta coletar(String termo, List<String> idsFontes, int paginas) {
        List<String> avisos = new ArrayList<>();
        List<Alvo> alvos = new ArrayList<>();
        Map<String, Integer> porFonte = new LinkedHashMap<>();
        for (String id : idsFontes) {
            FonteCotacao f = fontes.get(id);
            if (f == null) {
                avisos.add("loja desconhecida: " + id);
                continue;
            }
            porFonte.put(f.nome(), 0);
            for (String url : f.urls(termo, paginas)) alvos.add(new Alvo(f, url));
        }

        List<Anuncio> brutos = new ArrayList<>();
        Map<String, String> falhas = new LinkedHashMap<>(); // nome da loja → motivo (1ª falha)
        usarContexto(ctx -> {
            for (int i = 0; i < alvos.size(); i += ABAS_POR_ONDA) {
                List<Alvo> onda = alvos.subList(i, Math.min(alvos.size(), i + ABAS_POR_ONDA));
                // 1) dispara todas as navegações da onda
                Map<Alvo, Page> abas = new LinkedHashMap<>();
                for (Alvo a : onda) {
                    Page aba = ctx.newPage();
                    try {
                        aba.navigate(a.url(), new Page.NavigateOptions().setWaitUntil(WaitUntilState.COMMIT).setTimeout(45_000));
                        abas.put(a, aba);
                    } catch (PlaywrightException e) {
                        registrarFalha(falhas, a, "não abriu", e);
                        aba.close();
                    }
                }
                // 2) lê uma por uma — quando chega a vez das últimas, elas já carregaram
                for (var e : abas.entrySet()) {
                    Alvo a = e.getKey();
                    try {
                        List<Anuncio> achados = a.fonte().extrair(e.getValue());
                        brutos.addAll(achados);
                        porFonte.merge(a.fonte().nome(), achados.size(), Integer::sum);
                    } catch (RuntimeException ex) {
                        registrarFalha(falhas, a, "falhou ao ler a página", ex);
                    } finally {
                        e.getValue().close();
                    }
                }
            }
            return null;
        });

        falhas.forEach((loja, motivo) -> avisos.add(loja + " " + motivo));
        porFonte.forEach((loja, n) -> {
            if (n == 0 && !falhas.containsKey(loja)) avisos.add(loja + " não devolveu resultados para o termo");
        });

        ResolvedorPatrocinados.Resultado r = patrocinados.resolver(brutos);
        if (r.naoResolvidos() > 0) {
            avisos.add(r.naoResolvidos() + " link(s) patrocinado(s) mantidos como redirecionamento");
        }
        List<Anuncio> limpos = semDuplicados(r.anuncios());
        // o que fica para a tela é depois de tirar duplicados e anúncios sem preço
        Map<String, Integer> aproveitados = new LinkedHashMap<>();
        porFonte.keySet().forEach(k -> aproveitados.put(k, 0));
        limpos.forEach(a -> aproveitados.merge(a.fonte(), 1, Integer::sum));
        return new Coleta(limpos, avisos, aproveitados);
    }

    private static void registrarFalha(Map<String, String> falhas, Alvo a, String motivo, RuntimeException e) {
        log.warn("Cotação: {} {} ({}): {}", a.fonte().nome(), motivo, a.url(),
                String.valueOf(e.getMessage()).lines().findFirst().orElse(""));
        falhas.putIfAbsent(a.fonte().nome(), motivo);
    }

    /**
     * Abre o Chrome (sob a trava), entrega o contexto e fecha tudo ao fim. Único ponto que cria navegador: a coleta
     * e a ferramenta de exploração de lojas ({@code ExplorarLojaManualTest}) passam por aqui, com o mesmo perfil.
     */
    <T> T usarContexto(Function<BrowserContext, T> uso) {
        trava.lock();
        try (Playwright pw = Playwright.create(new Playwright.CreateOptions()
                // o Playwright tentaria baixar o Chromium dele no 1º uso; usamos o navegador instalado
                .setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
             BrowserContext ctx = abrirNavegador(pw)) {
            return uso.apply(ctx);
        } finally {
            trava.unlock();
        }
    }

    private BrowserContext abrirNavegador(Playwright pw) {
        List<String> args = new ArrayList<>(List.of(
                "--disable-blink-features=AutomationControlled",
                "--window-size=1440,900", "--no-first-run", "--no-default-browser-check",
                // a janela fica fora da tela e as lojas abrem em abas de fundo: sem isto o Chrome "economiza"
                // nelas (timers a 1/s, renderização pausada) e os sites que montam a lista em JavaScript demoram
                "--disable-background-timer-throttling", "--disable-backgrounding-occluded-windows",
                "--disable-renderer-backgrounding"));
        if (!cfg.janelaVisivel()) args.add("--window-position=-3000,-3000"); // existe, só não aparece na tela
        Path perfil = cfg.perfil().toAbsolutePath();
        return pw.chromium().launchPersistentContext(perfil, new BrowserType.LaunchPersistentContextOptions()
                .setChannel(cfg.canal())
                .setHeadless(false) // headless é bloqueado pelo Mercado Livre — não mude sem ler a classe
                .setLocale("pt-BR")
                .setViewportSize(1440, 900)
                .setArgs(args));
    }

    /**
     * Anúncio sem preço sai; o mesmo título na mesma loja (páginas 1 e 2, patrocinado e orgânico) fica uma vez só,
     * com o menor preço. Lojas diferentes nunca se fundem: o mesmo SSD na Kabum e na Pichau é justamente a comparação.
     */
    static List<Anuncio> semDuplicados(List<Anuncio> anuncios) {
        Map<String, Anuncio> melhor = new LinkedHashMap<>();
        for (Anuncio a : anuncios) {
            if (a.preco() == null || a.preco() <= 0) continue;
            String chave = a.fonte() + "|" + a.titulo().strip().toLowerCase(Locale.ROOT);
            Anuncio atual = melhor.get(chave);
            if (atual == null || a.preco() < atual.preco()) melhor.put(chave, a);
        }
        return List.copyOf(melhor.values());
    }
}
