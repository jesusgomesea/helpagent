package br.com.rdamasio.helpagent.loja;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import br.com.rdamasio.helpagent.common.NaoEncontrado;
import br.com.rdamasio.helpagent.common.OrigemRequisicao;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

/**
 * {@code GET /api/lojas} (busca para o autocomplete; {@code ?incluirInativas=true} para a tela de cadastro),
 * {@code GET /api/lojas/{numero}} e o cadastro: {@code POST}, {@code PUT /{numero}} e {@code PATCH /{numero}/ativa}.
 */
@RestController
@RequestMapping("/api/lojas")
public class LojaController {

    private final LojaService service;

    public LojaController(LojaService service) {
        this.service = service;
    }

    @GetMapping
    public List<LojaDto> listar(@RequestParam(required = false) String busca,
            @RequestParam(defaultValue = "false") boolean incluirInativas) {
        var lojas = incluirInativas ? service.todas() : service.buscar(busca);
        return lojas.stream().map(LojaDto::de).toList();
    }

    @GetMapping("/{numero}")
    public LojaDto porNumero(@PathVariable String numero) {
        return service.porNumero(numero).map(LojaDto::de)
                .orElseThrow(() -> new NaoEncontrado("Loja " + numero + " não cadastrada"));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public LojaDto criar(@RequestBody @Valid LojaForm form, HttpServletRequest req) {
        return LojaDto.de(service.criar(form, OrigemRequisicao.de(req)));
    }

    @PutMapping("/{numero}")
    public LojaDto atualizar(@PathVariable int numero, @RequestBody @Valid LojaForm form, HttpServletRequest req) {
        return LojaDto.de(service.atualizar(numero, form, OrigemRequisicao.de(req)));
    }

    public record Ativacao(boolean ativa) {
    }

    @PatchMapping("/{numero}/ativa")
    public LojaDto definirAtiva(@PathVariable int numero, @RequestBody Ativacao corpo, HttpServletRequest req) {
        return LojaDto.de(service.definirAtiva(numero, corpo.ativa(), OrigemRequisicao.de(req)));
    }
}
