package br.com.rdamasio.helpagent.common;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

import org.springframework.web.multipart.MultipartFile;

/** Um arquivo enviado pelo usuário (print do chamado, PDF do orçamento etc.). */
public record Documento(String mimeType, byte[] conteudo) {

    public boolean ehPdf() {
        return "application/pdf".equals(mimeType);
    }

    public boolean ehImagem() {
        return mimeType != null && mimeType.startsWith("image/");
    }

    public static Documento de(MultipartFile arquivo) {
        try {
            String tipo = arquivo.getContentType();
            String nome = arquivo.getOriginalFilename();
            // Alguns navegadores mandam PDF como octet-stream; a extensão desempata.
            if ((tipo == null || "application/octet-stream".equals(tipo)) && nome != null
                    && nome.toLowerCase().endsWith(".pdf")) {
                tipo = "application/pdf";
            }
            return new Documento(tipo, arquivo.getBytes());
        } catch (IOException e) {
            throw new UncheckedIOException("Falha ao ler o arquivo enviado", e);
        }
    }

    public static List<Documento> de(List<MultipartFile> arquivos) {
        return arquivos == null ? List.of() : arquivos.stream().filter(a -> !a.isEmpty()).map(Documento::de).toList();
    }
}
