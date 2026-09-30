package br.com.rdamasio.helpagent.historico;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import br.com.rdamasio.helpagent.armazenamento.ArmazenamentoArquivos;
import br.com.rdamasio.helpagent.common.NaoEncontrado;
import br.com.rdamasio.helpagent.common.OrigemRequisicao;
import br.com.rdamasio.helpagent.config.UsuarioAtual;
import br.com.rdamasio.helpagent.historico.BackupHistorico.ResultadoImportacao;
import br.com.rdamasio.helpagent.orcamento.ModoAquisicao;
import br.com.rdamasio.helpagent.orcamento.Orcamento;
import br.com.rdamasio.helpagent.orcamento.OrcamentoController;
import br.com.rdamasio.helpagent.orcamento.OrcamentoRepository;
import br.com.rdamasio.helpagent.orcamento.OrigemOrcamento;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Histórico central — qualquer máquina vê o que a equipe gerou, com quem gerou. "Apagar" manda para a lixeira
 * ({@link LixeiraHistorico}); de lá o orçamento volta ou é excluído de vez.
 */
@RestController
@RequestMapping("/api/historico")
public class HistoricoController {

    /** @param excluidoEm preenchido só na lixeira (junto de quem mandou para lá) */
    public record Item(Long id, Instant criadoEm, ModoAquisicao modo, String titulo, int lojaNumero, String lojaNome,
            String empresa, String chamadoNum, BigDecimal total, String nomeArquivo, String criadoPor,
            OrigemOrcamento origem, Instant excluidoEm, String excluidoPor) {

        static Item de(Orcamento o) {
            return new Item(o.getId(), o.getCriadoEm(), o.getModo(), o.getTitulo(), o.getLoja().getNumero(),
                    o.getLoja().getNome(), o.getLoja().getEmpresa(), o.getChamadoNum(), o.getTotal(),
                    o.getNomeArquivo(), o.getCriadoPor(), o.getOrigem(), o.getExcluidoEm(), o.getExcluidoPor());
        }
    }

    public record Pagina<T>(List<T> itens, int pagina, int tamanho, long total) {
    }

    private final OrcamentoRepository repo;
    private final ArmazenamentoArquivos armazenamento;
    private final BackupService backup;
    private final ChamadosJaOrcados jaOrcados;
    private final LixeiraHistorico lixeira;
    private final UsuarioAtual usuario;

    public HistoricoController(OrcamentoRepository repo, ArmazenamentoArquivos armazenamento, BackupService backup,
            ChamadosJaOrcados jaOrcados, LixeiraHistorico lixeira, UsuarioAtual usuario) {
        this.repo = repo;
        this.armazenamento = armazenamento;
        this.backup = backup;
        this.jaOrcados = jaOrcados;
        this.lixeira = lixeira;
        this.usuario = usuario;
    }

    /**
     * Orçamentos já gerados para algum dos chamados informados (texto livre: "1021069, 1021070").
     * A tela de revisão chama ao ler o chamado e quando o atendente edita o campo.
     */
    @GetMapping("/por-chamado")
    public List<Item> porChamado(@RequestParam String numeros) {
        return jaOrcados.buscar(ChamadosJaOrcados.numeros(numeros));
    }

    /** Backup completo do histórico (com os PDFs) — ver {@link BackupHistorico}. */
    @GetMapping("/exportar")
    public ResponseEntity<byte[]> exportar() {
        byte[] corpo = backup.exportar();
        String nome = "historico-orcamentos-" + LocalDate.now() + ".json";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(nome).build().toString())
                .body(corpo);
    }

    /** Adiciona os registros de um backup (deste sistema ou do HTML v3.5); repetidos são ignorados. */
    @PostMapping(path = "/importar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResultadoImportacao importar(@RequestPart("arquivo") MultipartFile arquivo) throws java.io.IOException {
        return backup.importar(arquivo.getBytes());
    }

    /**
     * @param modo    aba do histórico (OPEX, CAPEX, REQUISICAO); ausente = todos os tipos
     * @param lixeira true = a aba "Lixeira" (todos os tipos, só o que foi apagado)
     */
    @GetMapping
    @Transactional
    public Pagina<Item> listar(@RequestParam(required = false) String busca,
            @RequestParam(required = false) ModoAquisicao modo,
            @RequestParam(defaultValue = "false") boolean lixeira,
            @RequestParam(defaultValue = "0") int pagina,
            @RequestParam(defaultValue = "20") int tamanho) {
        if (lixeira) this.lixeira.limparVencidos();
        FiltroHistorico f = new FiltroHistorico(busca, lixeira ? null : modo, lixeira);
        Sort ordem = Sort.by(Sort.Direction.DESC, lixeira ? "excluidoEm" : "criadoEm");
        Page<Orcamento> p = repo.findAll(f.especificacao(),
                PageRequest.of(Math.max(pagina, 0), Math.clamp(tamanho, 1, 100), ordem));
        return new Pagina<>(p.getContent().stream().map(Item::de).toList(), p.getNumber(), p.getSize(), p.getTotalElements());
    }

    /**
     * Contagem para as abas (Todos, cada tipo e Lixeira), respeitando a busca digitada.
     * Tipos sem nenhum orçamento vêm com 0, para a aba aparecer mesmo vazia.
     */
    @GetMapping("/contagem")
    @Transactional(readOnly = true)
    public Map<String, Long> contagem(@RequestParam(required = false) String busca) {
        FiltroHistorico f = new FiltroHistorico(busca, null, false);
        Map<String, Long> r = new LinkedHashMap<>();
        long total = 0;
        for (ModoAquisicao m : ModoAquisicao.values()) {
            long n = repo.count(f.comModo(m).especificacao());
            r.put(m.name(), n);
            total += n;
        }
        r.put("TODOS", total);
        r.put("LIXEIRA", repo.count(f.naLixeira(true).especificacao()));
        return r;
    }

    @GetMapping("/{id}/pdf")
    @Transactional(readOnly = true)
    public ResponseEntity<byte[]> baixar(@PathVariable Long id) {
        Orcamento o = repo.findById(id).orElseThrow(() -> new NaoEncontrado("Orçamento " + id + " não encontrado"));
        byte[] bytes = armazenamento.ler(o.getPdfRef());
        return OrcamentoController.pdf(o.getNomeArquivo(), bytes).body(bytes);
    }

    /** "Apagar": vai para a lixeira (volta por {@link LixeiraHistorico#PRAZO}). */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> remover(@PathVariable Long id, HttpServletRequest req) {
        lixeira.mandar(id, quem(req));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/restaurar")
    public ResponseEntity<Void> restaurar(@PathVariable Long id, HttpServletRequest req) {
        lixeira.restaurar(id, quem(req));
        return ResponseEntity.noContent().build();
    }

    /** Exclui de vez (registro e PDF) — só o que já está na lixeira. */
    @DeleteMapping("/{id}/definitivo")
    public ResponseEntity<Void> excluirDeVez(@PathVariable Long id, HttpServletRequest req) {
        lixeira.excluirDeVez(id, quem(req));
        return ResponseEntity.noContent().build();
    }

    /** Sem login, quem é o IP; com login, o usuário (e o IP junto, para o log). */
    private String quem(HttpServletRequest req) {
        String u = usuario.identificador();
        String ip = OrigemRequisicao.de(req);
        return "anonimo".equals(u) ? ip : u + " (" + ip + ")";
    }
}
