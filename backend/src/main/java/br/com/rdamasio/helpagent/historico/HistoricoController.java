package br.com.rdamasio.helpagent.historico;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
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
import br.com.rdamasio.helpagent.historico.BackupHistorico.ResultadoImportacao;
import br.com.rdamasio.helpagent.orcamento.ModoAquisicao;
import br.com.rdamasio.helpagent.orcamento.Orcamento;
import br.com.rdamasio.helpagent.orcamento.OrcamentoController;
import br.com.rdamasio.helpagent.orcamento.OrcamentoRepository;

/** Histórico central — qualquer máquina vê o que a equipe gerou, com quem gerou. */
@RestController
@RequestMapping("/api/historico")
public class HistoricoController {

    public record Item(Long id, Instant criadoEm, ModoAquisicao modo, String titulo, int lojaNumero, String lojaNome,
            String empresa, String chamadoNum, BigDecimal total, String nomeArquivo, String criadoPor) {

        static Item de(Orcamento o) {
            return new Item(o.getId(), o.getCriadoEm(), o.getModo(), o.getTitulo(), o.getLoja().getNumero(),
                    o.getLoja().getNome(), o.getLoja().getEmpresa(), o.getChamadoNum(), o.getTotal(),
                    o.getNomeArquivo(), o.getCriadoPor());
        }
    }

    public record Pagina<T>(List<T> itens, int pagina, int tamanho, long total) {
    }

    private final OrcamentoRepository repo;
    private final ArmazenamentoArquivos armazenamento;
    private final BackupService backup;
    private final ChamadosJaOrcados jaOrcados;

    public HistoricoController(OrcamentoRepository repo, ArmazenamentoArquivos armazenamento, BackupService backup,
            ChamadosJaOrcados jaOrcados) {
        this.repo = repo;
        this.armazenamento = armazenamento;
        this.backup = backup;
        this.jaOrcados = jaOrcados;
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

    /** @param modo aba do histórico (OPEX, CAPEX, REQUISICAO); ausente = todos os tipos */
    @GetMapping
    @Transactional(readOnly = true)
    public Pagina<Item> listar(@RequestParam(required = false) String busca,
            @RequestParam(required = false) ModoAquisicao modo,
            @RequestParam(defaultValue = "0") int pagina,
            @RequestParam(defaultValue = "20") int tamanho) {
        Page<Orcamento> p = repo.buscar(termoLike(busca), modo,
                PageRequest.of(Math.max(pagina, 0), Math.clamp(tamanho, 1, 100), Sort.by(Sort.Direction.DESC, "criadoEm")));
        return new Pagina<>(p.getContent().stream().map(Item::de).toList(), p.getNumber(), p.getSize(), p.getTotalElements());
    }

    /**
     * Contagem por tipo para as abas ("Todos (104) · OPEX (0) · …"), respeitando a busca digitada.
     * Tipos sem nenhum orçamento vêm com 0, para a aba aparecer mesmo vazia.
     */
    @GetMapping("/contagem")
    @Transactional(readOnly = true)
    public Map<String, Long> contagem(@RequestParam(required = false) String busca) {
        Map<String, Long> r = new LinkedHashMap<>();
        for (ModoAquisicao m : ModoAquisicao.values()) r.put(m.name(), 0L);
        long total = 0;
        for (Object[] linha : repo.contarPorModo(termoLike(busca))) {
            long n = (Long) linha[1];
            r.put(((ModoAquisicao) linha[0]).name(), n);
            total += n;
        }
        r.put("TODOS", total);
        return r;
    }

    private static String termoLike(String busca) {
        return busca == null || busca.isBlank() ? null : "%" + busca.trim().toLowerCase(Locale.ROOT) + "%";
    }

    @GetMapping("/{id}/pdf")
    @Transactional(readOnly = true)
    public ResponseEntity<byte[]> baixar(@PathVariable Long id) {
        Orcamento o = repo.findById(id).orElseThrow(() -> new NaoEncontrado("Orçamento " + id + " não encontrado"));
        byte[] bytes = armazenamento.ler(o.getPdfRef());
        return OrcamentoController.pdf(o.getNomeArquivo(), bytes).body(bytes);
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<Void> remover(@PathVariable Long id) {
        Orcamento o = repo.findById(id).orElseThrow(() -> new NaoEncontrado("Orçamento " + id + " não encontrado"));
        repo.delete(o);
        armazenamento.remover(o.getPdfRef());
        return ResponseEntity.noContent().build();
    }
}
