package br.com.rdamasio.helpagent.extracao;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;

import br.com.rdamasio.helpagent.common.Documento;
import br.com.rdamasio.helpagent.config.HelpAgentProperties;

/**
 * Reaproveita a leitura da IA quando chegam exatamente os mesmos arquivos, no mesmo modo e na mesma ordem
 * (ex.: o usuário extraiu, deu erro na geração e extraiu de novo; ou dois atendentes com o mesmo chamado).
 * Responde na hora e não gasta quota — a quota gratuita do modelo principal é de poucas dezenas por dia.
 *
 * <p>Chave = SHA-256 de (modo + prompt + cada arquivo). Mudou o prompt, muda a chave: não serve resposta
 * velha depois de editar {@code prompts/extracao.txt}. Fica só em memória: reiniciar o backend limpa.
 *
 * <p>Também junta leituras <b>em andamento</b> ({@link #obter}): se os mesmos arquivos chegam enquanto a primeira
 * leitura ainda espera a IA (duplo clique, dois atendentes), a segunda espera a mesma resposta em vez de gastar
 * outra requisição. Vale mesmo com o cache desligado.
 */
@Component
public class CacheExtracao {

    /** Evita crescer sem limite num dia de muito uso; ao passar disso, os mais antigos saem. */
    private static final int MAX_ENTRADAS = 200;

    private record Entrada(ExtratorIa.Resultado resultado, Instant expira) {
    }

    private final Map<String, Entrada> entradas = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<ExtratorIa.Resultado>> emAndamento = new ConcurrentHashMap<>();

    /** @param reaproveitado veio do cache ou de uma leitura igual em andamento (não gastou cota) */
    public record Obtido(ExtratorIa.Resultado resultado, boolean reaproveitado) {
    }
    private final Duration validade;

    public CacheExtracao(HelpAgentProperties props) {
        this.validade = props.gemini().cacheExtracao();
    }

    public boolean ligado() {
        return validade != null && !validade.isZero() && !validade.isNegative();
    }

    public Optional<ExtratorIa.Resultado> buscar(String chave) {
        Entrada e = entradas.get(chave);
        if (e == null) return Optional.empty();
        if (Instant.now().isAfter(e.expira())) {
            entradas.remove(chave);
            return Optional.empty();
        }
        return Optional.of(e.resultado());
    }

    /** Cache → leitura igual em andamento → nova leitura com {@code ler}, nessa ordem. */
    public Obtido obter(String chave, Supplier<ExtratorIa.Resultado> ler) {
        Optional<ExtratorIa.Resultado> pronto = buscar(chave);
        if (pronto.isPresent()) return new Obtido(pronto.get(), true);
        CompletableFuture<ExtratorIa.Resultado> minha = new CompletableFuture<>();
        CompletableFuture<ExtratorIa.Resultado> outra = emAndamento.putIfAbsent(chave, minha);
        if (outra != null) {
            try {
                return new Obtido(outra.join(), true);
            } catch (CompletionException e) {
                if (e.getCause() instanceof RuntimeException r) throw r;
                throw e;
            }
        }
        try {
            ExtratorIa.Resultado r = ler.get();
            guardar(chave, r);
            minha.complete(r);
            return new Obtido(r, false);
        } catch (RuntimeException e) {
            minha.completeExceptionally(e);
            throw e;
        } finally {
            emAndamento.remove(chave, minha);
        }
    }

    public void guardar(String chave, ExtratorIa.Resultado resultado) {
        if (!ligado()) return;
        if (entradas.size() >= MAX_ENTRADAS) limpar();
        entradas.put(chave, new Entrada(resultado, Instant.now().plus(validade)));
    }

    public static String chave(String modo, String prompt, List<Documento> documentos) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update(modo.getBytes(StandardCharsets.UTF_8));
            sha.update(prompt.getBytes(StandardCharsets.UTF_8));
            for (Documento d : documentos) {
                // tamanho antes do conteúdo: fronteira entre arquivos sem ambiguidade
                sha.update(ByteBuffer.allocate(8).putLong(d.conteudo().length).array());
                sha.update(String.valueOf(d.mimeType()).getBytes(StandardCharsets.UTF_8));
                sha.update(d.conteudo());
            }
            return HexFormat.of().formatHex(sha.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponível", e);
        }
    }

    /** Remove o que expirou; se ainda estiver cheio, esvazia (cache é otimização, não pode virar vazamento). */
    private void limpar() {
        Instant agora = Instant.now();
        entradas.values().removeIf(e -> agora.isAfter(e.expira()));
        if (entradas.size() >= MAX_ENTRADAS) entradas.clear();
    }
}
