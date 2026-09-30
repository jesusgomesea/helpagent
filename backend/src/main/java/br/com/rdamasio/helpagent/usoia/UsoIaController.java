package br.com.rdamasio.helpagent.usoia;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/uso-ia}: painel de uso do Gemini (cota de cada modelo da cadeia, totais do dia, últimas chamadas).
 * Quem mostra é a página técnica /swagger/uso-ia do frontend (fora do menu do helpdesk).
 */
@RestController
@RequestMapping("/api/uso-ia")
public class UsoIaController {

    private final UsoIaService service;

    public UsoIaController(UsoIaService service) {
        this.service = service;
    }

    @GetMapping
    public UsoIaService.Painel painel() {
        return service.painel();
    }
}
