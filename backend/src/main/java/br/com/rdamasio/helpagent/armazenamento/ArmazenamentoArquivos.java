package br.com.rdamasio.helpagent.armazenamento;

/**
 * Onde ficam os PDFs gerados. A implementação local grava em disco; trocar por S3/MinIO/Blob
 * é implementar esta interface — quem usa só guarda a referência devolvida.
 */
public interface ArmazenamentoArquivos {

    /** @return referência opaca para ler/remover depois */
    String salvar(byte[] conteudo, String extensao);

    byte[] ler(String referencia);

    void remover(String referencia);
}
