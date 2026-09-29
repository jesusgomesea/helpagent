package br.com.rdamasio.helpagent.cotacao;

/** Abre o {@link CarimboPrint} (package-private) para os testes de outros pacotes. */
public final class CarimboPrintAcesso {

    private CarimboPrintAcesso() {
    }

    public static byte[] aplicar(byte[] imagem, String titulo, String url) {
        return CarimboPrint.aplicar(imagem, titulo, url);
    }
}
