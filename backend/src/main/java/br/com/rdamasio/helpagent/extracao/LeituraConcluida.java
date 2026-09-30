package br.com.rdamasio.helpagent.extracao;

import java.time.Instant;

/**
 * Evento: a IA terminou uma leitura (não publicado quando a resposta veio do cache — é a mesma leitura). Quem ouve é
 * o {@code qualidadeia}, que guarda o que foi lido para comparar com o que o atendente confirmar. Evento em vez de
 * chamada direta para a extração não depender do pacote que analisa a qualidade dela.
 *
 * @param id o mesmo id que agrupa as chamadas desta leitura na tabela uso_ia
 */
public record LeituraConcluida(String id, Instant momento, String modo, String modelo, int degrau, int arquivos,
        DadosExtraidos dados) {
}
