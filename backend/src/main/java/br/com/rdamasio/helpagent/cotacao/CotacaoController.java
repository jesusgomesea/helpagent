package br.com.rdamasio.helpagent.cotacao;

import java.text.Normalizer;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import br.com.rdamasio.helpagent.common.OrigemRequisicao;
import br.com.rdamasio.helpagent.config.HelpAgentProperties;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Cotação em lojas online (Kabum, Pichau, Terabyte, Mercado Livre, Dell, Lenovo) — funcionalidade à parte do
 * orçamento (tela /cotacao). Ver {@link CotacaoService}.
 */
@RestController
@RequestMapping("/api/cotacao")
public class CotacaoController {

    /**
     * @param paginas   1 a {@code helpagent.cotacao.max-paginas}
     * @param fontes    ids das lojas (ver {@link Estado#lojas}); vazio = as do nível 1
     * @param criterios ausentes caem no padrão de Suprimentos
     */
    public record Pedido(String termo, Integer paginas, List<String> fontes, CriteriosCotacao criterios) {
    }

    public record Reavaliacao(CriteriosCotacao criterios) {
    }

    public record Loja(String id, String nome, FonteCotacao.Grupo grupo) {
    }

    /** Nível com as lojas que ele marca, já resolvidas. */
    public record NivelBusca(int numero, String nome, String descricao, List<String> fontes) {
    }

    /** @param ocupado há cotação em andamento: a próxima espera na fila */
    public record Estado(boolean ocupado, CriteriosCotacao padrao, int maxPaginas, List<Loja> lojas,
            List<NivelBusca> niveis) {
    }

    /** yyyyMMdd — não usar BASIC_ISO_DATE: com fuso ele acrescenta o offset ("20260928-0300"). */
    private static final DateTimeFormatter DIA = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final CotacaoService service;
    private final PlanilhaCotacao planilha;
    private final HelpAgentProperties props;

    public CotacaoController(CotacaoService service, PlanilhaCotacao planilha, HelpAgentProperties props) {
        this.service = service;
        this.planilha = planilha;
        this.props = props;
    }

    @GetMapping("/estado")
    public Estado estado() {
        return new Estado(service.ocupado(), CriteriosCotacao.PADRAO, props.cotacao().maxPaginas(),
                service.fontes().stream().map(f -> new Loja(f.id(), f.nome(), f.grupo())).toList(),
                CotacaoService.NIVEIS.stream()
                        .map(n -> new NivelBusca(n.numero(), n.nome(), n.descricao(), service.fontesDoNivel(n.numero())))
                        .toList());
    }

    @PostMapping
    public CotacaoService.Resposta cotar(@RequestBody Pedido pedido, HttpServletRequest req) {
        return service.cotar(pedido.termo(), pedido.paginas() == null ? 1 : pedido.paginas(), pedido.fontes(),
                pedido.criterios(), OrigemRequisicao.de(req));
    }

    @PostMapping("/{id}/reavaliar")
    public CotacaoService.Resposta reavaliar(@PathVariable String id, @RequestBody Reavaliacao corpo) {
        return service.reavaliar(id, corpo.criterios());
    }

    @GetMapping("/{id}/planilha")
    public ResponseEntity<byte[]> planilha(@PathVariable String id) {
        CotacaoService.Guardada g = service.buscar(id);
        String nome = "Cotacao_" + nomeArquivo(g.termo()) + "_"
                + g.coletadoEm().atZone(props.fuso()).format(DIA) + ".xlsx";
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(nome).build().toString())
                .body(planilha.gerar(g));
    }

    /** "SSD 256GB (M.2)" → "SSD_256GB_M_2", até 40 caracteres, sem acento. */
    static String nomeArquivo(String termo) {
        String semAcento = Normalizer.normalize(termo, Normalizer.Form.NFKD).replaceAll("\\p{M}", "");
        String s = semAcento.replaceAll("[^A-Za-z0-9]+", "_").replaceAll("^_+|_+$", "");
        return s.length() > 40 ? s.substring(0, 40) : s;
    }
}
