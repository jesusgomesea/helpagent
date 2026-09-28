package br.com.rdamasio.helpagent.orcamento;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import br.com.rdamasio.helpagent.common.Documento;
import jakarta.validation.Valid;

/**
 * {@code POST /api/orcamentos}: gera o impresso e devolve o PDF. A parte {@code dados} é JSON
 * ({@link GerarOrcamentoRequest}); {@code chamados}/{@code orcamentos} são os arquivos anexados ao final.
 */
@RestController
@RequestMapping("/api/orcamentos")
public class OrcamentoController {

    private final OrcamentoService service;

    public OrcamentoController(OrcamentoService service) {
        this.service = service;
    }

    /**
     * Gera o impresso e devolve o PDF. Os arquivos originais vão junto porque são anexados
     * no final do PDF (formulário → chamados → orçamentos).
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> gerar(
            @RequestPart("dados") @Valid GerarOrcamentoRequest dados,
            @RequestPart(name = "chamados", required = false) List<MultipartFile> chamados,
            @RequestPart(name = "orcamentos", required = false) List<MultipartFile> orcamentos) {
        OrcamentoService.Gerado g = service.gerar(dados, Documento.de(chamados), Documento.de(orcamentos));
        return pdf(g.nomeArquivo(), g.pdf()).header("X-Orcamento-Id", String.valueOf(g.id())).body(g.pdf());
    }

    public static ResponseEntity.BodyBuilder pdf(String nome, byte[] bytes) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .contentLength(bytes.length)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(nome, StandardCharsets.UTF_8).build().toString());
    }
}
