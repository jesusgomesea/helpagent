package br.com.rdamasio.helpagent.extracao;

import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import br.com.rdamasio.helpagent.common.Documento;
import br.com.rdamasio.helpagent.orcamento.ModoAquisicao;

/**
 * {@code POST /api/extracoes}: recebe os arquivos (multipart) e devolve o que a IA leu + avisos para revisão.
 */
@RestController
@RequestMapping("/api/extracoes")
public class ExtracaoController {

    private final ExtracaoService service;

    public ExtracaoController(ExtracaoService service) {
        this.service = service;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ExtracaoService.Resposta extrair(
            @RequestParam ModoAquisicao modo,
            @RequestPart(name = "chamados", required = false) List<MultipartFile> chamados,
            @RequestPart(name = "orcamentos", required = false) List<MultipartFile> orcamentos) {
        return service.extrair(modo, Documento.de(chamados), Documento.de(orcamentos));
    }
}
