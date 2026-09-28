package br.com.rdamasio.helpagent.cotacao;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import br.com.rdamasio.helpagent.common.ErroNegocio;
import br.com.rdamasio.helpagent.common.NaoEncontrado;
import br.com.rdamasio.helpagent.config.HelpAgentProperties;

/**
 * Orquestra a cotação: coleta nas lojas escolhidas ({@link ColetorCotacao}) → avaliação ({@link MotorCotacao}) →
 * guarda em memória.
 *
 * <p>Cada cotação ganha um {@code id}. Com o id, a tela reavalia com outros critérios sem abrir o Chrome de novo
 * (só o motor roda: instantâneo) e baixa a planilha. No piloto em Python o cache era por termo — aqui o uso é
 * compartilhado e duas pessoas cotando o mesmo termo se sobrescreveriam. Nada vai para o banco: é insumo de
 * decisão, não registro (HANDOFF §1.3); reiniciar o backend limpa.
 */
@Service
public class CotacaoService {

    private static final Logger log = LoggerFactory.getLogger(CotacaoService.class);
    private static final int MAX_GUARDADAS = 100;
    /**
     * Níveis de busca: cada um soma um grupo de lojas ao anterior. A tela mostra os níveis como atalhos e deixa
     * marcar loja por loja. Nível 1 é o padrão: o varejo de TI costuma ter o melhor preço à vista para peças.
     */
    public record Nivel(int numero, String nome, String descricao, List<FonteCotacao.Grupo> grupos) {
    }

    public static final List<Nivel> NIVEIS = List.of(
            new Nivel(1, "Varejo de TI", "Kabum, Pichau, Terabyte", List.of(FonteCotacao.Grupo.VAREJO_TI)),
            new Nivel(2, "+ Marketplaces", "também Amazon e Mercado Livre",
                    List.of(FonteCotacao.Grupo.VAREJO_TI, FonteCotacao.Grupo.MARKETPLACE)),
            new Nivel(3, "+ Fabricantes", "também Dell e Lenovo (notebooks, desktops, monitores)",
                    List.of(FonteCotacao.Grupo.VAREJO_TI, FonteCotacao.Grupo.MARKETPLACE, FonteCotacao.Grupo.FABRICANTE)));

    /**
     * Uma cotação guardada: a coleta crua (para reavaliar) e o último resultado (para a planilha).
     *
     * @param porFonte anúncios aproveitados de cada loja consultada (0 = não devolveu nada)
     */
    record Guardada(String termo, Instant coletadoEm, double segundos, List<String> avisos, Map<String, Integer> porFonte,
            List<Anuncio> anuncios, ResultadoCotacao resultado, Instant expira) {

        Guardada com(ResultadoCotacao novo, Duration validade) {
            return new Guardada(termo, coletadoEm, segundos, avisos, porFonte, anuncios, novo, Instant.now().plus(validade));
        }
    }

    /**
     * O que a API devolve.
     *
     * @param segundos      tempo da coleta (a reavaliação mantém o da coleta original)
     * @param esperouFila   havia outra cotação em andamento quando esta chegou
     * @param porFonte      anúncios aproveitados de cada loja consultada, na ordem pedida
     */
    public record Resposta(String id, String termo, Instant coletadoEm, double segundos, boolean esperouFila,
            List<String> avisos, Map<String, Integer> porFonte, ResultadoCotacao resultado) {
    }

    private final ColetorCotacao coletor;
    private final MotorCotacao motor;
    private final HelpAgentProperties.Cotacao cfg;
    private final Map<String, Guardada> guardadas = new ConcurrentHashMap<>();

    public CotacaoService(ColetorCotacao coletor, MotorCotacao motor, HelpAgentProperties props) {
        this.coletor = coletor;
        this.motor = motor;
        this.cfg = props.cotacao();
    }

    /** Lojas dos grupos de um nível, na ordem em que estão cadastradas. */
    public List<String> fontesDoNivel(int numero) {
        Nivel n = NIVEIS.stream().filter(x -> x.numero() == numero).findFirst().orElse(NIVEIS.getFirst());
        return coletor.fontes().stream().filter(f -> n.grupos().contains(f.grupo())).map(FonteCotacao::id).toList();
    }

    public List<FonteCotacao> fontes() {
        return coletor.fontes();
    }

    /**
     * @param fontes ids das lojas a consultar; vazio = as do nível 1
     */
    public Resposta cotar(String termo, int paginas, List<String> fontes, CriteriosCotacao criterios, String origem) {
        String t = termo == null ? "" : termo.strip();
        if (t.isEmpty()) throw new ErroNegocio("Informe o produto a cotar.");
        List<String> ids = fontes == null || fontes.isEmpty() ? fontesDoNivel(1) : List.copyOf(fontes);
        int pags = Math.clamp(paginas, 1, cfg.maxPaginas());
        boolean fila = coletor.ocupado();
        log.info("Cotação '{}' em {} ({} pág.) pedida por {}{}", t, ids, pags, origem,
                fila ? " — aguardando outra em andamento" : "");

        long t0 = System.nanoTime();
        Instant inicio = Instant.now();
        ColetorCotacao.Coleta coleta = coletor.coletar(t, ids, pags);
        double segundos = Math.round((System.nanoTime() - t0) / 1e8) / 10.0;
        if (coleta.anuncios().isEmpty()) {
            log.warn("Cotação '{}' sem anúncios em {}s: {}", t, segundos, coleta.avisos());
            throw new FalhaColeta("Nenhum anúncio coletado para \"" + t + "\".", coleta.avisos());
        }

        ResultadoCotacao r = motor.avaliar(coleta.anuncios(), criterios);
        String id = UUID.randomUUID().toString();
        guardar(id, new Guardada(t, inicio, segundos, coleta.avisos(), coleta.porFonte(), coleta.anuncios(), r,
                Instant.now().plus(cfg.cacheResultado())));
        log.info("Cotação '{}': {} anúncios {}, {} elegíveis, {}s", t, coleta.anuncios().size(), coleta.porFonte(),
                r.elegiveis().size(), segundos);
        return new Resposta(id, t, inicio, segundos, fila, coleta.avisos(), coleta.porFonte(), r);
    }

    /** Mesmos anúncios, critérios novos — não abre navegador. */
    public Resposta reavaliar(String id, CriteriosCotacao criterios) {
        Guardada g = buscar(id);
        ResultadoCotacao r = motor.avaliar(g.anuncios(), criterios);
        Guardada nova = g.com(r, cfg.cacheResultado());
        guardadas.put(id, nova);
        return new Resposta(id, g.termo(), g.coletadoEm(), g.segundos(), false, g.avisos(), g.porFonte(), r);
    }

    /** A cotação com o último resultado avaliado (o que a planilha exporta). */
    public Guardada buscar(String id) {
        return Optional.ofNullable(guardadas.get(id))
                .filter(x -> Instant.now().isBefore(x.expira()))
                .orElseThrow(() -> new NaoEncontrado("Cotação expirada ou inexistente — refaça a busca."));
    }

    public boolean ocupado() {
        return coletor.ocupado();
    }

    private void guardar(String id, Guardada g) {
        if (guardadas.size() >= MAX_GUARDADAS) {
            Instant agora = Instant.now();
            guardadas.values().removeIf(x -> agora.isAfter(x.expira()));
            if (guardadas.size() >= MAX_GUARDADAS) {
                guardadas.entrySet().stream().min(Map.Entry.comparingByValue(
                        (a, b) -> a.coletadoEm().compareTo(b.coletadoEm())))
                        .ifPresent(e -> guardadas.remove(e.getKey()));
            }
        }
        guardadas.put(id, g);
    }
}
