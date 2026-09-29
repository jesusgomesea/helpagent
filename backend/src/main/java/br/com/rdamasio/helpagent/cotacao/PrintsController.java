package br.com.rdamasio.helpagent.cotacao;

import java.util.Arrays;
import java.util.List;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import br.com.rdamasio.helpagent.common.Documento;
import br.com.rdamasio.helpagent.common.OrigemRequisicao;
import jakarta.servlet.http.HttpServletRequest;

/** Prints das páginas de produto do orçamento por cotação ({@link PrintsCotacao}). */
@RestController
@RequestMapping("/api/cotacao")
public class PrintsController {

    /** @param urls anúncios da cotação, a opção escolhida primeiro (até 3) */
    public record Pedido(List<String> urls) {
    }

    private final PrintsCotacao prints;

    public PrintsController(PrintsCotacao prints) {
        this.prints = prints;
    }

    @PostMapping("/{cotacaoId}/prints")
    public List<PrintsCotacao.Print> solicitar(@PathVariable String cotacaoId, @RequestBody Pedido pedido,
            HttpServletRequest req) {
        return prints.solicitar(cotacaoId, pedido.urls(), OrigemRequisicao.de(req));
    }

    /** Situação de vários prints de uma vez: a cesta consulta a cada poucos segundos enquanto algum captura. */
    @GetMapping("/prints")
    public List<PrintsCotacao.Print> situacao(@RequestParam String ids) {
        return prints.situacao(Arrays.stream(ids.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList());
    }

    @GetMapping(path = "/prints/{id}/imagem", produces = MediaType.IMAGE_JPEG_VALUE)
    public ResponseEntity<byte[]> imagem(@PathVariable String id) {
        // a imagem de um id muda (tirar de novo, anexar à mão): a tela acrescenta ?v= e o navegador não guarda
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(prints.imagem(id));
    }

    @PostMapping("/prints/{id}/recapturar")
    public PrintsCotacao.Print recapturar(@PathVariable String id) {
        return prints.recapturar(id);
    }

    @PutMapping(path = "/prints/{id}/imagem", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public PrintsCotacao.Print anexarManual(@PathVariable String id, @RequestPart("arquivo") MultipartFile arquivo,
            HttpServletRequest req) {
        return prints.substituirManual(id, Documento.de(arquivo), OrigemRequisicao.de(req));
    }
}
