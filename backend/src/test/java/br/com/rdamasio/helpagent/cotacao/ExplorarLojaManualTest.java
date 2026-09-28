package br.com.rdamasio.helpagent.cotacao;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.WaitUntilState;

import br.com.rdamasio.helpagent.config.HelpAgentProperties;

/**
 * Ferramenta para mapear uma loja nova (ou diagnosticar uma que parou): abre cada URL no MESMO Chrome da coleta
 * e salva em target/exploracao/ o HTML, os dados estruturados que o site embute (JSON-LD, __NEXT_DATA__) e uma
 * captura de tela — o que o site mostra para o robô, que pode ser diferente do que mostra para uma pessoa.
 *
 * <pre>./mvnw test -Dtest=ExplorarLojaManualTest -Dcotacao.explorar="kabum=https://www.kabum.com.br/busca/ssd-256gb;dell=https://..."</pre>
 */
@EnabledIfSystemProperty(named = "cotacao.explorar", matches = ".+")
class ExplorarLojaManualTest {

    private static final String SONDA = """
            () => {
              const ld = [...document.querySelectorAll('script[type="application/ld+json"]')].map(s => s.textContent);
              const next = document.getElementById('__NEXT_DATA__');
              const precos = [...document.querySelectorAll('body *')]
                .filter(e => e.children.length === 0 && /R\\$\\s?\\d/.test(e.textContent)).length;
              return { titulo: document.title, ld: ld, next: next ? next.textContent : null, precos: precos,
                       texto: document.body ? document.body.innerText.slice(0, 1500) : '' };
            }""";

    @Test
    void explorar() throws Exception {
        Map<String, String> alvos = new LinkedHashMap<>();
        for (String par : System.getProperty("cotacao.explorar").split(";")) {
            String[] kv = par.split("=", 2);
            alvos.put(kv[0].strip(), kv[1].strip());
        }
        var cfg = new HelpAgentProperties.Cotacao("chrome", Path.of("dados/navegador"), 3, Duration.ofMinutes(30),
                Boolean.getBoolean("cotacao.visivel"));
        var props = new HelpAgentProperties(null, null, null, null, cfg, null, List.of());
        var coletor = new ColetorCotacao(List.of(), new ResolvedorPatrocinados(), props);
        Path saida = Files.createDirectories(Path.of("target/exploracao"));

        coletor.usarContexto(ctx -> {
            Page pagina = ctx.pages().isEmpty() ? ctx.newPage() : ctx.pages().getFirst();
            for (var alvo : alvos.entrySet()) {
                String id = alvo.getKey();
                try {
                    long t0 = System.nanoTime();
                    Response resp = pagina.navigate(alvo.getValue(), new Page.NavigateOptions().setTimeout(60_000)
                            // "load" nunca chega em site com rastreadores pendurados: basta o HTML pronto
                            .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                    pagina.waitForTimeout(Long.getLong("cotacao.espera", 7000)); // sites em JS carregam a lista depois
                    long ms = (System.nanoTime() - t0) / 1_000_000;
                    @SuppressWarnings("unchecked")
                    Map<String, Object> s = (Map<String, Object>) pagina.evaluate(SONDA);
                    Files.writeString(saida.resolve(id + ".html"), pagina.content());
                    if (s.get("next") != null) Files.writeString(saida.resolve(id + "-next.json"), (String) s.get("next"));
                    List<?> ld = (List<?>) s.get("ld");
                    if (!ld.isEmpty()) Files.writeString(saida.resolve(id + "-ld.json"), "[" + String.join(",\n", ld.stream().map(Object::toString).toList()) + "]");
                    pagina.screenshot(new Page.ScreenshotOptions().setPath(saida.resolve(id + ".png")));
                    // roda o script de extração da loja (se existir) na página ao vivo: é o que a coleta faria
                    if (FonteComScript.class.getResource("/cotacao/extrair-" + id + ".js") != null) {
                        Object r = pagina.evaluate(FonteComScript.carregarScript("/cotacao/extrair-" + id + ".js"));
                        List<?> itens = r instanceof List<?> l ? l : List.of();
                        System.out.printf("  script extrair-%s.js: %d itens%s%n", id, itens.size(),
                                itens.isEmpty() ? "" : " · 1º: " + itens.getFirst());
                    }
                    System.out.println("  sonda extra: " + pagina.evaluate(
                            "() => ({nextF: Array.isArray(self.__next_f) ? self.__next_f.length : null, scripts: document.scripts.length})"));
                    System.out.printf("%n=== %s  %s%n  status=%s em %d ms · final=%s%n  título=%s · elementos com R$=%s · JSON-LD=%d · __NEXT_DATA__=%s%n  texto: %s%n",
                            id, alvo.getValue(), resp == null ? "?" : resp.status(), ms, pagina.url(), s.get("titulo"),
                            s.get("precos"), ld.size(), s.get("next") != null,
                            String.valueOf(s.get("texto")).replaceAll("\\s+", " ").substring(0, Math.min(400, String.valueOf(s.get("texto")).length())));
                } catch (Exception e) { // inclui IOException ao gravar: a lambda não pode lançar checada
                    System.out.printf("%n=== %s  FALHOU: %s%n", id, String.valueOf(e.getMessage()).lines().limit(6)
                            .map(String::strip).filter(l -> !l.isEmpty() && !l.equals("Error {")).reduce((a, b) -> a + " | " + b).orElse(""));
                }
            }
            return null;
        });
    }
}
