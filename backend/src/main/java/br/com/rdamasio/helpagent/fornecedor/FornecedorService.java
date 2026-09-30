package br.com.rdamasio.helpagent.fornecedor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.rdamasio.helpagent.common.Cnpj;
import br.com.rdamasio.helpagent.common.ErroNegocio;
import br.com.rdamasio.helpagent.common.NaoEncontrado;

/**
 * Fornecedores conhecidos: reconhecer o que a IA leu, vincular a linha do orçamento ao gerar (cadastrando quem é
 * novo) e o cadastro pela tela. O cadastro cresce sozinho com o uso — ninguém precisa cadastrar antes — e cada nome
 * lido diferente do padronizado vira apelido, para a próxima leitura já reconhecer.
 */
@Service
@Transactional(readOnly = true)
public class FornecedorService {

    private static final Logger log = LoggerFactory.getLogger(FornecedorService.class);

    private final FornecedorRepository repo;

    public FornecedorService(FornecedorRepository repo) {
        this.repo = repo;
    }

    /** O cadastrado que corresponde ao que a IA leu (só entre os ativos). */
    public Optional<Fornecedor> reconhecer(String nomeLido, String cnpjLido) {
        return ReconhecimentoFornecedor.achar(repo.findByAtivoTrueOrderByNome(), nomeLido, cnpjLido);
    }

    /**
     * Fornecedor de uma linha do orçamento, na hora de gerar: o cadastrado (reconhecido pelo nome confirmado ou pelo
     * CNPJ) ou um novo. Aprende o nome que a IA leu como apelido, e o CNPJ se ainda não tinha.
     *
     * @param nome      nome confirmado pelo atendente (o que vai para o histórico); vazio = linha sem fornecedor
     * @param cnpj      CNPJ lido/digitado (opcional; inválido é ignorado)
     * @param nomeLido  nome como a IA leu no documento (opcional)
     */
    @Transactional
    public Optional<Fornecedor> vincular(String nome, String cnpj, String nomeLido) {
        if (nome == null || nome.isBlank()) return Optional.empty();
        String cnpjOk = cnpj != null && Cnpj.valido(cnpj) ? Cnpj.formatar(cnpj) : null;
        List<Fornecedor> todos = repo.findAllByOrderByNome();
        Fornecedor f = ReconhecimentoFornecedor.achar(todos, nome, cnpjOk)
                .orElseGet(() -> {
                    Fornecedor novo = repo.save(new Fornecedor(nome.strip(), cnpjOk, Instant.now()));
                    log.info("Fornecedor {} CADASTRADO automaticamente: {} {}", novo.getId(), novo.getNome(),
                            cnpjOk == null ? "" : cnpjOk);
                    return novo;
                });
        if (!f.isAtivo()) f.definirAtivo(true); // voltou a ser usado
        if (cnpjOk != null && f.getCnpj() == null && todos.stream().noneMatch(x -> cnpjOk.equals(x.getCnpj()))) {
            f.definirCnpjSeVazio(cnpjOk);
        }
        if (f.aprenderApelido(nomeLido) | f.aprenderApelido(nome)) {
            log.info("Fornecedor {} ({}) aprendeu apelido(s): {}", f.getId(), f.getNome(), f.getApelidos());
        }
        return Optional.of(f);
    }

    public List<Fornecedor> todos() {
        return repo.findAllByOrderByNome();
    }

    public List<Fornecedor> ativos() {
        return repo.findByAtivoTrueOrderByNome();
    }

    public List<Object[]> uso() {
        return repo.uso();
    }

    @Transactional
    public Fornecedor criar(FornecedorForm f, String origem) {
        String cnpj = validarCnpj(f.cnpj());
        exigirCnpjLivre(cnpj, null);
        Fornecedor novo = new Fornecedor(f.nome().strip(), cnpj, Instant.now());
        novo.atualizar(f.nome().strip(), cnpj, f.apelidos());
        repo.save(novo);
        log.info("Fornecedor {} CRIADO por {}: {}", novo.getId(), origem, descrever(novo));
        return novo;
    }

    @Transactional
    public Fornecedor atualizar(Long id, FornecedorForm f, String origem) {
        Fornecedor atual = buscar(id);
        String antes = descrever(atual);
        String cnpj = validarCnpj(f.cnpj());
        exigirCnpjLivre(cnpj, id);
        atual.atualizar(f.nome().strip(), cnpj, f.apelidos());
        log.info("Fornecedor {} ALTERADO por {}: antes [{}] depois [{}]", id, origem, antes, descrever(atual));
        return atual;
    }

    /** Desativado some da sugestão e do reconhecimento; os orçamentos antigos continuam apontando para ele. */
    @Transactional
    public Fornecedor definirAtivo(Long id, boolean ativo, String origem) {
        Fornecedor f = buscar(id);
        f.definirAtivo(ativo);
        log.info("Fornecedor {} {} por {}", id, ativo ? "REATIVADO" : "DESATIVADO", origem);
        return f;
    }

    private Fornecedor buscar(Long id) {
        return repo.findById(id).orElseThrow(() -> new NaoEncontrado("Fornecedor " + id + " não cadastrado"));
    }

    private static String validarCnpj(String cnpj) {
        if (cnpj == null || cnpj.isBlank()) return null;
        if (!Cnpj.valido(cnpj)) throw new ErroNegocio("CNPJ inválido.", List.of("o CNPJ " + cnpj + " não confere"));
        return Cnpj.formatar(cnpj);
    }

    private void exigirCnpjLivre(String cnpj, Long proprio) {
        if (cnpj == null) return;
        repo.findAllByOrderByNome().stream()
                .filter(x -> cnpj.equals(x.getCnpj()) && !x.getId().equals(proprio))
                .findFirst()
                .ifPresent(x -> {
                    throw new ErroNegocio("CNPJ já cadastrado.", List.of("o CNPJ " + cnpj + " é do fornecedor " + x.getNome()));
                });
    }

    private static String descrever(Fornecedor f) {
        return "nome=" + f.getNome() + " cnpj=" + f.getCnpj() + " apelidos=" + f.getApelidos();
    }
}
