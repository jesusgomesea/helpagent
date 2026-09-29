package br.com.rdamasio.helpagent.common;

import java.util.List;

/**
 * Chamada a um recurso desligado neste servidor ({@code helpagent.recursos.*}). Vira 503 no {@code TratadorErros}:
 * não é erro de quem chamou nem falha do sistema — o recurso simplesmente não está disponível aqui.
 */
public class RecursoDesligado extends RuntimeException {

    private final List<String> problemas;

    public RecursoDesligado(String mensagem, List<String> problemas) {
        super(mensagem);
        this.problemas = List.copyOf(problemas);
    }

    public List<String> problemas() {
        return problemas;
    }
}
