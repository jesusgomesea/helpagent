package br.com.rdamasio.helpagent.cotacao;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import br.com.rdamasio.helpagent.common.Documento;
import br.com.rdamasio.helpagent.common.ErroNegocio;
import br.com.rdamasio.helpagent.common.NaoEncontrado;
import br.com.rdamasio.helpagent.config.HelpAgentProperties;
import jakarta.annotation.PreDestroy;
import tools.jackson.databind.json.JsonMapper;

/**
 * Prints das páginas de produto para o orçamento por cotação: cada item escolhido leva 3 prints (a opção
 * escolhida e 2 alternativas), que a validação manual confere. Ver docs/MANUTENCAO.md §8.
 *
 * <p>A captura é em segundo plano: o atendente escolhe o item e já pesquisa o próximo enquanto o Chrome
 * fotografa. As capturas entram numa fila única (um lote por item) e dividem a trava do Chrome com a coleta.
 *
 * <p>Cada print fica em disco ({@code helpagent.cotacao.prints}): {@code <id>.jpg} e {@code <id>.json} com os
 * dados do anúncio. Os dados vêm da cotação guardada no servidor, nunca da tela — o Chrome do servidor só abre
 * endereço que uma loja devolveu (a tela não consegue mandá-lo abrir outro site).
 */
@Service
public class PrintsCotacao {

    private static final Logger log = LoggerFactory.getLogger(PrintsCotacao.class);
    private static final DateTimeFormatter QUANDO = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");
    private static final Pattern ID = Pattern.compile("[0-9a-f-]{36}");
    /** Um item leva a escolhida + 2 alternativas. */
    public static final int MAX_POR_ITEM = 3;

    public enum Situacao {
        /** na fila ou com o Chrome aberto */
        CAPTURANDO,
        /** print tirado pelo sistema */
        PRONTO,
        /** não deu: {@link Print#motivo()} diz por quê; a tela oferece tirar de novo ou anexar à mão */
        FALHOU,
        /** o atendente anexou o próprio print no lugar */
        MANUAL
    }

    /**
     * @param preco       preço à vista coletado (o que o print deve mostrar)
     * @param capturadoEm quando a imagem foi tirada ou anexada; null enquanto captura
     * @param alerta      o print saiu, mas a conferência automática não achou o preço visível nele (null = conferido;
     *                    sempre null no print anexado à mão, que é responsabilidade de quem anexou)
     */
    public record Print(String id, String url, String fonte, String titulo, Double preco, Situacao situacao,
            String motivo, Instant capturadoEm, String alerta) {

        /** Nova situação; o alerta só vale para o print automático que acabou de sair (ver {@link #comAlerta}). */
        Print com(Situacao s, String m, Instant quando) {
            return new Print(id, url, fonte, titulo, preco, s, m, quando, null);
        }

        Print comAlerta(String a) {
            return new Print(id, url, fonte, titulo, preco, situacao, motivo, capturadoEm, a);
        }

        public boolean temImagem() {
            return situacao == Situacao.PRONTO || situacao == Situacao.MANUAL;
        }
    }

    private final ColetorCotacao coletor;
    private final CotacaoService cotacoes;
    private final JsonMapper json;
    private final HelpAgentProperties props;
    private final Path pasta;
    private final Map<String, Print> prints = new ConcurrentHashMap<>();
    /** Uma fila só: o Chrome é um por vez de qualquer jeito (trava do coletor). */
    private final ExecutorService fila = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("prints-cotacao").factory());

    public PrintsCotacao(ColetorCotacao coletor, CotacaoService cotacoes, JsonMapper json, HelpAgentProperties props) {
        this.coletor = coletor;
        this.cotacoes = cotacoes;
        this.json = json;
        this.props = props;
        this.pasta = props.cotacao().prints().toAbsolutePath().normalize();
    }

    @PreDestroy
    void encerrar() {
        fila.shutdownNow();
    }

    /**
     * Enfileira os prints de um item: {@code urls} são os anúncios da cotação {@code cotacaoId}, a escolhida primeiro.
     * Devolve na hora, com os prints em {@link Situacao#CAPTURANDO}; a tela acompanha por {@link #situacao}.
     */
    public List<Print> solicitar(String cotacaoId, List<String> urls, String origem) {
        if (urls == null || urls.isEmpty() || urls.size() > MAX_POR_ITEM) {
            throw new ErroNegocio("Escolha de 1 a " + MAX_POR_ITEM + " anúncios para fotografar.");
        }
        CotacaoService.Guardada c = cotacoes.buscar(cotacaoId);
        List<Print> novos = new ArrayList<>();
        for (String url : urls) {
            Anuncio a = c.anuncios().stream().filter(x -> x.url().equals(url)).findFirst()
                    .orElseThrow(() -> new ErroNegocio("Anúncio fora desta cotação — refaça a busca.", List.of(url)));
            novos.add(new Print(UUID.randomUUID().toString(), a.url(), a.fonte(), a.titulo(), a.preco(),
                    Situacao.CAPTURANDO, null, null, null));
        }
        novos.forEach(this::gravar);
        limparVencidos();
        log.info("Prints de '{}' pedidos por {}: {}", c.termo(), origem, novos.stream().map(Print::fonte).toList());
        enfileirar(novos);
        return novos;
    }

    /** Tira de novo um print (falhou, ou a página mudou). */
    public Print recapturar(String id) {
        Print p = buscar(id);
        if (p.situacao() == Situacao.CAPTURANDO) return p;
        Print novo = p.com(Situacao.CAPTURANDO, null, null);
        gravar(novo);
        enfileirar(List.of(novo));
        return novo;
    }

    /** O atendente anexa o próprio print no lugar do automático (loja bloqueou, página diferente…). */
    public Print substituirManual(String id, Documento arquivo, String origem) {
        Print p = buscar(id);
        if (!arquivo.ehImagem()) throw new ErroNegocio("Anexe uma imagem (PNG ou JPEG) do print.");
        Instant agora = Instant.now();
        byte[] jpg;
        try {
            jpg = CarimboPrint.aplicar(arquivo.conteudo(), p.fonte() + " · print anexado à mão em " + quando(agora), p.url());
        } catch (IllegalArgumentException e) {
            throw new ErroNegocio("Não consegui ler a imagem.", List.of(e.getMessage()));
        }
        escrever(arquivoImagem(id), jpg);
        Print novo = p.com(Situacao.MANUAL, null, agora);
        gravar(novo);
        log.info("Print {} ({}) substituído à mão por {}", id, p.fonte(), origem);
        return novo;
    }

    public List<Print> situacao(List<String> ids) {
        return ids.stream().map(this::buscar).toList();
    }

    public Print buscar(String id) {
        if (id == null || !ID.matcher(id).matches()) throw new NaoEncontrado("Print inexistente.");
        return Optional.ofNullable(prints.get(id)).or(() -> carregar(id))
                .orElseThrow(() -> new NaoEncontrado("Print expirado ou inexistente — escolha o item de novo."));
    }

    public byte[] imagem(String id) {
        Print p = buscar(id);
        if (!p.temImagem()) throw new NaoEncontrado("Print ainda não disponível.");
        try {
            return Files.readAllBytes(arquivoImagem(p.id()));
        } catch (IOException e) {
            throw new NaoEncontrado("Arquivo do print não encontrado.");
        }
    }

    /** "28/09/2026 21:40:12", no fuso da empresa — é o que aparece no print e no resumo. */
    public String quando(Instant i) {
        return ZonedDateTime.ofInstant(i, props.fuso()).format(QUANDO);
    }

    private void enfileirar(List<Print> lote) {
        fila.submit(() -> {
            try {
                List<ColetorCotacao.Captura> capturas = coletor.capturar(
                        lote.stream().map(p -> new ColetorCotacao.PaginaProduto(p.url(), p.preco())).toList());
                for (int i = 0; i < lote.size(); i++) concluir(lote.get(i), capturas.get(i));
            } catch (RuntimeException e) {
                log.warn("Prints: o Chrome falhou: {}", e.getMessage());
                lote.forEach(p -> gravar(p.com(Situacao.FALHOU, "o navegador do servidor não abriu", null)));
            }
        });
    }

    private void concluir(Print p, ColetorCotacao.Captura c) {
        if (c.png() == null) {
            gravar(p.com(Situacao.FALHOU, c.falha(), null));
            return;
        }
        Instant agora = Instant.now();
        escrever(arquivoImagem(p.id()), CarimboPrint.aplicar(c.png(), p.fonte() + " · capturado em " + quando(agora), p.url()));
        if (c.alerta() != null) log.info("Print {} ({}) com alerta: {}", p.id(), p.fonte(), c.alerta());
        gravar(p.com(Situacao.PRONTO, null, agora).comAlerta(c.alerta()));
    }

    private void gravar(Print p) {
        prints.put(p.id(), p);
        escrever(pasta.resolve(p.id() + ".json"), json.writeValueAsBytes(p));
    }

    /**
     * Depois de reiniciar o backend, o print sai do disco. Se estava capturando, a captura se perdeu com o
     * reinício: vira falha, para a tela oferecer tirar de novo.
     */
    private Optional<Print> carregar(String id) {
        Path arq = pasta.resolve(id + ".json");
        if (!Files.exists(arq)) return Optional.empty();
        try {
            Print p = json.readValue(Files.readAllBytes(arq), Print.class);
            if (p.situacao() == Situacao.CAPTURANDO) p = p.com(Situacao.FALHOU, "o servidor reiniciou durante a captura", null);
            prints.put(id, p);
            return Optional.of(p);
        } catch (IOException | RuntimeException e) {
            log.warn("Print {} ilegível: {}", id, e.getMessage());
            return Optional.empty();
        }
    }

    private void limparVencidos() {
        Instant limite = Instant.now().minus(props.cotacao().validadePrints());
        prints.values().removeIf(p -> p.capturadoEm() != null && p.capturadoEm().isBefore(limite));
        if (!Files.isDirectory(pasta)) return;
        try (Stream<Path> arquivos = Files.list(pasta)) {
            arquivos.filter(a -> {
                try {
                    return Files.getLastModifiedTime(a).toInstant().isBefore(limite);
                } catch (IOException e) {
                    return false;
                }
            }).forEach(a -> {
                try {
                    Files.deleteIfExists(a);
                } catch (IOException ignorado) {
                    // tenta de novo na próxima limpeza
                }
            });
        } catch (IOException e) {
            log.warn("Limpeza dos prints falhou: {}", e.getMessage());
        }
    }

    private Path arquivoImagem(String id) {
        return pasta.resolve(id + ".jpg");
    }

    private void escrever(Path destino, byte[] conteudo) {
        try {
            Files.createDirectories(destino.getParent());
            Files.write(destino, conteudo);
        } catch (IOException e) {
            throw new UncheckedIOException("Não foi possível gravar o print", e);
        }
    }
}
