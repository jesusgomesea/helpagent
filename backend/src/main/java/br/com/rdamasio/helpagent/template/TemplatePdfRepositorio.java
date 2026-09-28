package br.com.rdamasio.helpagent.template;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Carrega o PDF em branco de cada impresso. Hoje vem do classpath (extraído do HTML v3.5
 * por {@code tools/extrair_legado.py}); a tela de administração de templates troca esta
 * fonte pelo armazenamento de arquivos sem mudar quem consome.
 */
@Component
public class TemplatePdfRepositorio {

    public byte[] carregar(TemplateCodigo codigo) {
        ClassPathResource recurso = new ClassPathResource("pdf-templates/" + codigo.name() + ".pdf");
        try (InputStream in = recurso.getInputStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Template " + codigo + " não encontrado", e);
        }
    }
}
