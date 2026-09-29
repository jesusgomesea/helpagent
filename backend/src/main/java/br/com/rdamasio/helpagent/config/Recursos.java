package br.com.rdamasio.helpagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Liga/desliga de cada recurso ({@code helpagent.recursos.*}, variáveis {@code RECURSO_IA} e {@code RECURSO_COTACAO}).
 * Existe para a integração numa aplicação maior (docs/INTEGRACAO.md §5): o núcleo — orçamento, impresso, histórico e
 * lojas — roda em qualquer servidor, mas
 * <ul>
 *   <li><b>cotação</b> dirige um Chrome <b>com janela</b> (o Mercado Livre bloqueia headless; MANUTENCAO §7): num
 *       contêiner ou servidor sem sessão de desktop ela não funciona, e deve ser desligada ou ir para outra máquina;</li>
 *   <li><b>ia</b> depende da chave do Gemini e de saída para a internet.</li>
 * </ul>
 * Desligado, as rotas do recurso respondem 503 com explicação ({@link GuardaRecursos}) e o frontend, que lê isto em
 * {@code /api/parametros}, esconde o que não existe. Fica num record próprio (e não no {@link HelpAgentProperties})
 * para não mudar o construtor que os testes usam.
 *
 * @param ia       leitura de orçamento por IA e o painel "Uso da IA"
 * @param cotacao  cotação em lojas online e o orçamento por cotação (prints)
 */
@ConfigurationProperties("helpagent.recursos")
public record Recursos(
        @DefaultValue("true") boolean ia,
        @DefaultValue("true") boolean cotacao) {
}
