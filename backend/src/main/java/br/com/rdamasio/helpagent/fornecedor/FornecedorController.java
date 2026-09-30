package br.com.rdamasio.helpagent.fornecedor;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

import br.com.rdamasio.helpagent.common.OrigemRequisicao;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

/**
 * {@code GET /api/fornecedores} (ativos, para a sugestão na revisão; {@code ?incluirInativos=true} com o uso de cada
 * um, para a tela de cadastro), {@code POST}, {@code PUT /{id}} e {@code PATCH /{id}/ativo}.
 */
@RestController
@RequestMapping("/api/fornecedores")
public class FornecedorController {

    /** @param orcamentos em quantos orçamentos (fora da lixeira) aparece; {@code ultimoUso} o mais recente */
    public record FornecedorDto(Long id, String nome, String cnpj, List<String> apelidos, boolean ativo,
            long orcamentos, Instant ultimoUso) {

        public static FornecedorDto de(Fornecedor f) {
            return new FornecedorDto(f.getId(), f.getNome(), f.getCnpj(), f.getApelidos(), f.isAtivo(), 0, null);
        }
    }

    public record Ativacao(boolean ativo) {
    }

    private final FornecedorService service;

    public FornecedorController(FornecedorService service) {
        this.service = service;
    }

    @GetMapping
    public List<FornecedorDto> listar(@RequestParam(defaultValue = "false") boolean incluirInativos) {
        if (!incluirInativos) return service.ativos().stream().map(FornecedorDto::de).toList();
        Map<Long, Object[]> uso = new HashMap<>();
        for (Object[] u : service.uso()) uso.put((Long) u[0], u);
        return service.todos().stream().map(f -> {
            Object[] u = uso.get(f.getId());
            return new FornecedorDto(f.getId(), f.getNome(), f.getCnpj(), f.getApelidos(), f.isAtivo(),
                    u == null ? 0 : (Long) u[1], u == null ? null : (Instant) u[2]);
        }).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public FornecedorDto criar(@RequestBody @Valid FornecedorForm form, HttpServletRequest req) {
        return FornecedorDto.de(service.criar(form, OrigemRequisicao.de(req)));
    }

    @PutMapping("/{id}")
    public FornecedorDto atualizar(@PathVariable Long id, @RequestBody @Valid FornecedorForm form, HttpServletRequest req) {
        return FornecedorDto.de(service.atualizar(id, form, OrigemRequisicao.de(req)));
    }

    @PatchMapping("/{id}/ativo")
    public FornecedorDto definirAtivo(@PathVariable Long id, @RequestBody Ativacao corpo, HttpServletRequest req) {
        return FornecedorDto.de(service.definirAtivo(id, corpo.ativo(), OrigemRequisicao.de(req)));
    }
}
