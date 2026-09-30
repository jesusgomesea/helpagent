package br.com.rdamasio.helpagent.qualidadeia;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/qualidade-ia?dias=90}: onde a IA mais erra, pelo que os atendentes corrigem antes de gerar.
 * Mostrado na página técnica /swagger/qualidade-ia (fora do menu, ao lado de /swagger/uso-ia).
 */
@RestController
@RequestMapping("/api/qualidade-ia")
public class QualidadeIaController {

    private final QualidadeIaService service;

    public QualidadeIaController(QualidadeIaService service) {
        this.service = service;
    }

    @GetMapping
    public QualidadeIaService.Relatorio relatorio(@RequestParam(defaultValue = "90") int dias) {
        return service.relatorio(dias);
    }
}
