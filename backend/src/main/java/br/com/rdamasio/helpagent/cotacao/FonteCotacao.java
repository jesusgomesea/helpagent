package br.com.rdamasio.helpagent.cotacao;

import java.util.List;

import com.microsoft.playwright.Page;

/**
 * Uma loja onde a cotação busca anúncios. Para acrescentar outra: um {@code @Component} que estende
 * {@link FonteComScript} (ou implementa esta interface) e devolve o contrato de {@link Anuncio} —
 * {@link ColetorCotacao} a encontra sozinho, e motor, tela e planilha não mudam.
 *
 * <p>Duas fases, para o coletor abrir todas as lojas <b>em paralelo</b> (uma aba cada) e só depois ler cada aba:
 * {@link #urls} diz o que abrir; {@link #extrair} espera a lista aparecer na aba e lê os anúncios.
 */
public interface FonteCotacao {

    /** Grupo da loja; os níveis de busca da tela são combinações de grupos. */
    enum Grupo {
        /** Varejo especializado em TI (Kabum, Pichau, Terabyte) — nível 1 */
        VAREJO_TI,
        /** Marketplaces com muitos vendedores (Amazon, Mercado Livre) — nível 2 */
        MARKETPLACE,
        /** Loja do próprio fabricante (Dell, Lenovo) — nível 3 */
        FABRICANTE
    }

    /** Identificador usado na API e nos avisos (ex.: "kabum"). */
    String id();

    /** Nome para exibir e gravar em {@link Anuncio#fonte()} (ex.: "Kabum"). */
    String nome();

    Grupo grupo();

    /** Páginas de resultado a abrir, uma URL por página (a 1ª primeiro). */
    List<String> urls(String termo, int paginas);

    /**
     * Espera a lista de resultados aparecer na aba (já navegada para uma das {@link #urls}) e extrai os anúncios.
     * Página sem resultados devolve lista vazia; falha de estrutura (layout mudou) pode lançar exceção.
     */
    List<Anuncio> extrair(Page aba);
}
