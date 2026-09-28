package br.com.rdamasio.helpagent.armazenamento;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;
import java.util.UUID;

import org.springframework.stereotype.Component;

import br.com.rdamasio.helpagent.common.NaoEncontrado;
import br.com.rdamasio.helpagent.config.HelpAgentProperties;

/**
 * Guarda os PDFs em disco, em {@code <diretorio>/AAAA/MM/<uuid>.pdf} ({@code helpagent.armazenamento.diretorio}).
 * O banco só guarda a referência relativa; mover a pasta inteira preserva o histórico.
 */
@Component
public class ArmazenamentoLocal implements ArmazenamentoArquivos {

    private final Path raiz;

    public ArmazenamentoLocal(HelpAgentProperties props) {
        this.raiz = props.armazenamento().diretorio().toAbsolutePath().normalize();
    }

    @Override
    public String salvar(byte[] conteudo, String extensao) {
        YearMonth mes = YearMonth.now();
        String ref = "%d/%02d/%s.%s".formatted(mes.getYear(), mes.getMonthValue(), UUID.randomUUID(), extensao);
        Path destino = resolver(ref);
        try {
            Files.createDirectories(destino.getParent());
            Files.write(destino, conteudo);
            return ref;
        } catch (IOException e) {
            throw new UncheckedIOException("Não foi possível gravar o arquivo", e);
        }
    }

    @Override
    public byte[] ler(String referencia) {
        Path arquivo = resolver(referencia);
        if (!Files.exists(arquivo)) throw new NaoEncontrado("Arquivo não encontrado no armazenamento");
        try {
            return Files.readAllBytes(arquivo);
        } catch (IOException e) {
            throw new UncheckedIOException("Não foi possível ler o arquivo", e);
        }
    }

    @Override
    public void remover(String referencia) {
        try {
            Files.deleteIfExists(resolver(referencia));
        } catch (IOException e) {
            throw new UncheckedIOException("Não foi possível remover o arquivo", e);
        }
    }

    /** A referência vem do banco, mas nunca deixa sair da raiz (proteção contra "../"). */
    private Path resolver(String referencia) {
        Path p = raiz.resolve(referencia).normalize();
        if (!p.startsWith(raiz)) throw new IllegalArgumentException("Referência de arquivo inválida");
        return p;
    }
}
