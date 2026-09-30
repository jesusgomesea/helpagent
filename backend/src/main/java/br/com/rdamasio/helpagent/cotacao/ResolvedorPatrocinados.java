package br.com.rdamasio.helpagent.cotacao;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

/**
 * Troca o link de rastreamento dos anúncios patrocinados pela URL real do produto.
 *
 * <p>Anúncio pago não expõe a URL no DOM, só {@code click1.mercadolivre.com.br/...}, que responde 302 para o
 * anúncio. Pedimos sem seguir o redirecionamento e lemos o {@code Location}. Se falhar, o chamador mantém o link
 * de rastreamento — ele também abre o anúncio, só que é feio e expira. Nenhum item fica sem link.
 */
@Component
public class ResolvedorPatrocinados {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final int PARALELOS = 8;
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36";

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(TIMEOUT)
            .build();

    /** Devolve a lista com as URLs trocadas onde deu certo, e quantos patrocinados ficaram sem resolver. */
    public record Resultado(List<Anuncio> anuncios, int naoResolvidos) {
    }

    public Resultado resolver(List<Anuncio> anuncios) {
        List<Anuncio> saida = new ArrayList<>(anuncios);
        List<Integer> alvos = new ArrayList<>();
        for (int i = 0; i < saida.size(); i++) {
            Anuncio a = saida.get(i);
            // só o rastreamento do ML (click1) redireciona; as outras lojas já entregam o link limpo
            if (a.patrocinado() && a.url().contains("click1.")) alvos.add(i);
        }
        if (alvos.isEmpty()) return new Resultado(saida, 0);

        int falhas = 0;
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(PARALELOS, alvos.size()));
        try {
            List<Future<Optional<String>>> futuros = new ArrayList<>();
            for (int i : alvos) {
                String url = saida.get(i).url();
                futuros.add(pool.submit(() -> destino(url)));
            }
            for (int k = 0; k < alvos.size(); k++) {
                Optional<String> destino;
                try {
                    destino = futuros.get(k).get(TIMEOUT.toSeconds() * 3, TimeUnit.SECONDS);
                } catch (Exception e) {
                    destino = Optional.empty();
                }
                int i = alvos.get(k);
                if (destino.isPresent()) saida.set(i, saida.get(i).comUrl(destino.get()));
                else falhas++;
            }
        } finally {
            pool.shutdownNow();
        }
        return new Resultado(saida, falhas);
    }

    /** URL de destino do 3xx, sem query nem âncora; vazio se o servidor não redirecionou. */
    Optional<String> destino(String urlRastreamento) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(urlRastreamento))
                    .timeout(TIMEOUT).header("User-Agent", UA).GET().build();
            HttpResponse<Void> r = http.send(req, HttpResponse.BodyHandlers.discarding());
            if (r.statusCode() / 100 != 3) return Optional.empty();
            return r.headers().firstValue("Location")
                    .filter(l -> l.startsWith("http"))
                    .map(l -> l.split("[?#]", 2)[0]);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
