package br.com.rdamasio.helpagent.cotacao;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import br.com.rdamasio.helpagent.config.HelpAgentProperties;
import tools.jackson.databind.json.JsonMapper;

/**
 * Coleta REAL nas lojas, com o Chrome desta máquina. Desligado por padrão (a suíte nunca abre navegador).
 * Serve para diagnosticar quando uma loja parar e para regravar a amostra do teste de paridade do motor:
 *
 * <pre>./mvnw test -Dtest=ColetaRealManualTest -Dcotacao.real=true [-Dcotacao.termo="ssd 256gb"]
 *     [-Dcotacao.fontes=kabum,pichau] [-Dcotacao.paginas=1] [-Dcotacao.visivel=true] [-Dcotacao.gravar=true]</pre>
 *
 * {@code -Dcotacao.gravar=true} grava a amostra do motor e só faz sentido com {@code -Dcotacao.fontes=mercadolivre}
 * (o gabarito de paridade é do piloto, que só conhecia o Mercado Livre).
 */
@EnabledIfSystemProperty(named = "cotacao.real", matches = "true")
class ColetaRealManualTest {

    private static final List<FonteCotacao> TODAS = List.of(new FonteKabum(), new FontePichau(), new FonteTerabyte(),
            new FonteAmazon(), new FonteMercadoLivre(), new FonteDell(), new FonteLenovo());

    @Test
    void coletaNasLojas() throws Exception {
        String termo = System.getProperty("cotacao.termo", "ssd 256gb");
        List<String> fontes = Arrays.stream(System.getProperty("cotacao.fontes",
                String.join(",", TODAS.stream().map(FonteCotacao::id).toList())).split(",")).map(String::strip).toList();
        var cfg = new HelpAgentProperties.Cotacao("chrome", Path.of("dados/navegador"), 3, Duration.ofMinutes(30),
                Boolean.getBoolean("cotacao.visivel"));
        var props = new HelpAgentProperties(null, null, null, null, cfg, null, List.of());
        var coletor = new ColetorCotacao(TODAS, new ResolvedorPatrocinados(), props);

        long t0 = System.nanoTime();
        ColetorCotacao.Coleta c = coletor.coletar(termo, fontes, Integer.getInteger("cotacao.paginas", 1));
        long ms = (System.nanoTime() - t0) / 1_000_000;

        System.out.printf("%n  '%s' em %s: %d anúncios em %d ms%n  por loja: %s%n  avisos: %s%n", termo, fontes,
                c.anuncios().size(), ms, c.porFonte(), c.avisos());
        for (String loja : c.porFonte().keySet()) {
            List<Anuncio> daLoja = c.anuncios().stream().filter(a -> a.fonte().equals(loja)).toList();
            long semNota = daLoja.stream().filter(a -> a.nota() == null).count();
            System.out.printf("%n  --- %s: %d (sem nota: %d, loja própria: %d)%n", loja, daLoja.size(), semNota,
                    daLoja.stream().filter(Anuncio::vendedorProprio).count());
            daLoja.stream().limit(2).forEach(a -> System.out.printf("    R$ %.2f (de %s) nota=%s · %s%n      %s%n",
                    a.preco(), a.precoDe(), a.nota(), a.titulo().length() > 110 ? a.titulo().substring(0, 110) + "…" : a.titulo(), a.url()));
        }

        assertThat(c.anuncios()).isNotEmpty();
        if (Boolean.getBoolean("cotacao.gravar")) {
            Path destino = Path.of("src/test/resources/cotacao/amostra-" + FonteMercadoLivre.slugPiloto(termo) + ".json");
            Files.createDirectories(destino.getParent());
            Files.writeString(destino, JsonMapper.builder().build().writerWithDefaultPrettyPrinter()
                    .writeValueAsString(c.anuncios()));
            System.out.println("  amostra gravada em " + destino);
        }
    }
}
