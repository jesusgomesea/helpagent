package br.com.rdamasio.helpagent.loja;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.rdamasio.helpagent.common.Cnpj;
import br.com.rdamasio.helpagent.common.ErroNegocio;
import br.com.rdamasio.helpagent.common.NaoEncontrado;

/**
 * Busca de lojas com as mesmas regras do HTML v3.5 (número normalizado: "023" = "23"; nome por "contém")
 * e o cadastro pela tela de Lojas.
 *
 * <p>Não há login: qualquer um do helpdesk cadastra ou edita. Por isso toda alteração vai para o log com o
 * antes, o depois e o IP de quem fez — é a trilha para descobrir quem mudou um CNPJ.
 */
@Service
@Transactional(readOnly = true)
public class LojaService {

    private static final Logger log = LoggerFactory.getLogger(LojaService.class);

    private final LojaRepository repo;

    public LojaService(LojaRepository repo) {
        this.repo = repo;
    }

    /** "023", " 23 " e "23" encontram a mesma loja — a IA devolve o número como aparece no chamado. */
    public Optional<Loja> porNumero(String numero) {
        Integer n = normalizarNumero(numero);
        return n == null ? Optional.empty() : repo.findByNumeroAndAtivaTrue(n);
    }

    /** Primeira loja cujo nome contém o termo (sem diferenciar maiúsculas), como no HTML v3.5. */
    public Optional<Loja> porNome(String nome) {
        if (nome == null || nome.trim().length() < 2) return Optional.empty();
        String termo = nome.trim().toLowerCase(Locale.ROOT);
        return repo.findByAtivaTrueOrderByNumero().stream()
                .filter(l -> l.getNome().toLowerCase(Locale.ROOT).contains(termo))
                .findFirst();
    }

    /** Resolve a loja lida pela IA: número primeiro, nome como segunda chance. */
    public Optional<Loja> resolver(String numero, String nome) {
        return porNumero(numero).or(() -> porNome(nome));
    }

    /** Lista para o autocomplete; filtra por número exato ou nome contendo o termo. Só ativas. */
    public List<Loja> buscar(String termo) {
        List<Loja> todas = repo.findByAtivaTrueOrderByNumero();
        if (termo == null || termo.isBlank()) return todas;
        String t = termo.trim().toLowerCase(Locale.ROOT);
        Integer n = normalizarNumero(t);
        return todas.stream()
                .filter(l -> (n != null && l.getNumero() == n) || l.getNome().toLowerCase(Locale.ROOT).contains(t))
                .toList();
    }

    /** Todas, inclusive inativas — para a tela de cadastro. */
    public List<Loja> todas() {
        return repo.findAllByOrderByNumero();
    }

    @Transactional
    public Loja criar(LojaForm f, String origem) {
        if (repo.findByNumero(f.numero()).isPresent()) {
            throw new ErroNegocio("Loja já cadastrada.", List.of("já existe loja com o número " + f.numero()
                    + " (se estiver desativada, reative-a em vez de cadastrar de novo)"));
        }
        Loja l = new Loja(f.numero(), normalizar(f.nome()), validarCnpj(f.cnpj()), normalizar(f.empresa()), f.template());
        definirCadastro(l, f);
        repo.save(l);
        log.info("Loja {} CRIADA por {}: {}", l.getNumero(), origem, descrever(l));
        return l;
    }

    /** Edita nome, CNPJ, empresa e impresso. O número não muda (ver {@link Loja#atualizar}). */
    @Transactional
    public Loja atualizar(int numero, LojaForm f, String origem) {
        Loja l = repo.findByNumero(numero).orElseThrow(() -> new NaoEncontrado("Loja " + numero + " não cadastrada"));
        String antes = descrever(l);
        l.atualizar(normalizar(f.nome()), validarCnpj(f.cnpj()), normalizar(f.empresa()), f.template());
        definirCadastro(l, f);
        log.info("Loja {} ALTERADA por {}: antes [{}] depois [{}]", numero, origem, antes, descrever(l));
        return l;
    }

    @Transactional
    public Loja definirAtiva(int numero, boolean ativa, String origem) {
        Loja l = repo.findByNumero(numero).orElseThrow(() -> new NaoEncontrado("Loja " + numero + " não cadastrada"));
        l.definirAtiva(ativa);
        log.info("Loja {} {} por {}", numero, ativa ? "REATIVADA" : "DESATIVADA", origem);
        return l;
    }

    private static String validarCnpj(String cnpj) {
        if (!Cnpj.valido(cnpj)) {
            throw new ErroNegocio("CNPJ inválido.", List.of("o CNPJ " + cnpj + " não confere (dígitos verificadores)"));
        }
        return Cnpj.formatar(cnpj);
    }

    private static void definirCadastro(Loja l, LojaForm f) {
        l.definirCadastro(opcional(f.razaoSocial()), opcional(f.inscricaoEstadual()), opcional(f.cidade()), opcional(f.uf()));
    }

    /** Campo opcional: em branco vira nulo; preenchido segue o padrão em maiúsculas. */
    private static String opcional(String s) {
        return s == null || s.isBlank() ? null : normalizar(s);
    }

    /** O cadastro existente é todo em maiúsculas (ex.: "DAMASIO PE"); mantém o padrão. */
    private static String normalizar(String s) {
        return s.trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    }

    private static String descrever(Loja l) {
        return "nome=" + l.getNome() + " cnpj=" + l.getCnpj() + " empresa=" + l.getEmpresa() + " template=" + l.getTemplate()
                + " razao=" + l.getRazaoSocial() + " ie=" + l.getInscricaoEstadual() + " cidade=" + l.getCidade() + "/" + l.getUf();
    }

    static Integer normalizarNumero(String valor) {
        if (valor == null) return null;
        String v = valor.trim();
        if (v.isEmpty() || !v.chars().allMatch(Character::isDigit)) return null;
        try {
            return Integer.valueOf(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
