package br.com.rdamasio.helpagent.common;

/**
 * Recurso pedido não existe (loja, orçamento, arquivo). Vira HTTP 404 no {@link TratadorErros}.
 */
public class NaoEncontrado extends RuntimeException {

    public NaoEncontrado(String mensagem) {
        super(mensagem);
    }
}
