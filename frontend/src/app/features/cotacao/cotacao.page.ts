import { ChangeDetectionStrategy, Component, computed, inject, signal, viewChild } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { mensagensDeErro, salvarArquivo } from '../../core/api';
import { Avisos } from '../../core/avisos';
import { Faixa } from '../../layout/faixa';
import { Icone } from '../../layout/icone';
import { CurrencyPipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { Avaliado, CotacaoApi, CriteriosCotacao, GrupoLoja, LojaCotacao, NivelBusca, RespostaCotacao } from './cotacao.api';
import { CestaCotacao, MAX_ITENS_CESTA, alternativasPara } from './cesta.store';

const ROTULO_GRUPO: Record<GrupoLoja, string> = {
  VAREJO_TI: 'Varejo de TI',
  MARKETPLACE: 'Marketplaces',
  FABRICANTE: 'Fabricantes',
};
import { CriteriosCotacaoPainel } from './criterios';
import { ResultadoCotacaoView } from './resultado';

/**
 * Cotação em lojas online (funcionalidade à parte do orçamento, ampliada do piloto de Suprimentos): digita o
 * produto, escolhe onde buscar (nível 1 = varejo de TI, 2 = + marketplaces, 3 = + fabricantes, ou loja a loja),
 * o servidor coleta os anúncios com o Chrome e devolve um ranking pelos critérios do painel.
 *
 * Fluxo: "Cotar" coleta (as lojas em paralelo; uma cotação por vez no servidor). Com resultado na tela, mexer
 * num critério só reavalia os mesmos anúncios (instantâneo). Termo, lojas ou páginas novos → nova coleta.
 * Nada é gravado: o resultado vale 30 min para baixar a planilha.
 *
 * Orçamento por cotação: "escolher para o orçamento" põe o anúncio na cesta ({@link CestaCotacao}) com mais 2
 * opções para comparar, e o servidor fotografa as 3 páginas. A barra no rodapé leva à tela de revisão e geração.
 */
@Component({
  selector: 'ha-cotacao',
  imports: [Faixa, Icone, CriteriosCotacaoPainel, ResultadoCotacaoView, RouterLink, CurrencyPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <ha-faixa sobretitulo="Compras · Análise de mercado" titulo="Cotação"
      subtitulo="Digite o item e escolha onde buscar. O sistema varre as lojas, corta quem não atende aos requisitos mínimos e ordena o que sobrou pelo peso que você deu a preço, prazo, reputação e marca." />
    <main class="container">
      <form class="cot-busca" (submit)="cotar($event)" autocomplete="off">
        <label>Produto a cotar
          <input #termo name="termo" placeholder="ex: ssd 256gb, mouse sem fio logitech, monitor 24 polegadas"
            [disabled]="cotando()" [value]="ultimoTermo()">
        </label>
        <label class="cot-paginas" title="Páginas de resultado por loja">Profundidade
          <select name="paginas" [disabled]="cotando()">
            @for (n of opcoesPaginas(); track n) { <option [value]="n">{{ n }} página{{ n > 1 ? 's' : '' }} por loja</option> }
          </select>
        </label>
        <button class="btn-primario" type="submit" [disabled]="cotando() || !padrao() || !selecionadas().size">
          <ha-icone nome="busca" [tamanho]="16" /> Cotar agora
        </button>
      </form>
      <section class="cot-onde" aria-label="Onde buscar">
        <div class="cot-onde-cab">
          <span class="rotulo-secao">Onde buscar</span>
          <div class="seg" role="group" aria-label="Nível de busca">
            @for (n of niveis(); track n.numero) {
              <button type="button" [class.ativo]="nivelAtual() === n.numero" (click)="escolherNivel(n.numero)"
                [title]="n.descricao" [disabled]="cotando()">{{ n.numero }} · {{ n.nome }}</button>
            }
          </div>
          @if (nivelAtual() === null) { <span class="cot-personalizado">seleção personalizada</span> }
        </div>
        <div class="cot-lojas">
          @for (g of grupos(); track g.grupo) {
            <div class="cot-lojas-grupo">
              <span>{{ g.rotulo }}</span>
              @for (l of g.lojas; track l.id) {
                <label class="cot-loja-chip" [class.marcada]="selecionadas().has(l.id)">
                  <input type="checkbox" [checked]="selecionadas().has(l.id)" (change)="alternarLoja(l.id)" [disabled]="cotando()">
                  {{ l.nome }}
                </label>
              }
            </div>
          }
        </div>
      </section>

      <div class="cot-sugestoes">
        <span>Testar com</span>
        @for (s of sugestoes; track s.termo) {
          <button type="button" [disabled]="cotando() || !padrao() || !selecionadas().size" (click)="testar(s)">{{ s.termo }}</button>
        }
      </div>
      @if (ocupado() && !cotando()) {
        <div class="status alerta"><b>Há uma cotação em andamento no servidor.</b> A sua entra na fila e começa assim que ela terminar.</div>
      }
      @if (erros().length) {
        <div class="status erro"><b>Não foi possível cotar.</b><ul>@for (e of erros(); track $index) { <li>{{ e }}</li> }</ul></div>
      }

      <ha-criterios-cotacao [padrao]="padrao()" (alterado)="criteriosMudaram($event)" />

      @if (resposta(); as r) {
        <ha-resultado-cotacao [resposta]="r" [atualizando]="reavaliando()" (baixarPlanilha)="baixarPlanilha()"
          [escolhidas]="cesta.urlsEscolhidas()" [cestaCheia]="cesta.itens().length >= maxItens" (escolher)="escolher(r, $event)" />
      }
    </main>

    @if (cesta.itens().length) {
      <aside class="cesta-barra" aria-label="Orçamento por cotação">
        <div class="cesta-info">
          <b>Orçamento por cotação</b>
          <span>{{ cesta.itens().length }}/{{ maxItens }} ite{{ cesta.itens().length > 1 ? 'ns' : 'm' }} · {{ cesta.total() | currency: 'BRL' }}</span>
          @if (cesta.capturando()) { <span class="cesta-capturando">tirando {{ cesta.capturando() }} print{{ cesta.capturando() > 1 ? 's' : '' }}…</span> }
          @if (cesta.falhas()) { <span class="cesta-falha">{{ cesta.falhas() }} print{{ cesta.falhas() > 1 ? 's' : '' }} com falha</span> }
        </div>
        <a class="btn-acao" routerLink="/orcamento-cotacao">Revisar e gerar →</a>
      </aside>
    }
  `,
})
export class CotacaoPage {
  private readonly api = inject(CotacaoApi);
  private readonly avisos = inject(Avisos);
  private readonly painel = viewChild.required(CriteriosCotacaoPainel);
  protected readonly cesta = inject(CestaCotacao);
  protected readonly maxItens = MAX_ITENS_CESTA;

  /** Carregado uma vez: trocar o padrão reaplica no painel e apagaria os critérios que o usuário ajustou. */
  protected readonly padrao = signal<CriteriosCotacao | null>(null);
  /** Há cotação em andamento no servidor (a próxima espera na fila). */
  protected readonly ocupado = signal(false);
  protected readonly opcoesPaginas = signal([1]);
  protected readonly resposta = signal<RespostaCotacao | null>(null);
  protected readonly ultimoTermo = signal('');
  protected readonly cotando = signal(false);
  protected readonly reavaliando = signal(false);
  protected readonly erros = signal<string[]>([]);

  protected readonly lojas = signal<LojaCotacao[]>([]);
  protected readonly niveis = signal<NivelBusca[]>([]);
  /** Lojas marcadas para a próxima busca (ids). Começa no nível 1. */
  protected readonly selecionadas = signal<Set<string>>(new Set());

  /** Lojas agrupadas para os chips, na ordem dos grupos. */
  protected readonly grupos = computed(() =>
    (Object.keys(ROTULO_GRUPO) as GrupoLoja[])
      .map((grupo) => ({ grupo, rotulo: ROTULO_GRUPO[grupo], lojas: this.lojas().filter((l) => l.grupo === grupo) }))
      .filter((g) => g.lojas.length),
  );

  /** Nível que corresponde exatamente às lojas marcadas, ou null se a seleção foi feita loja a loja. */
  protected readonly nivelAtual = computed(() => {
    const sel = this.selecionadas();
    const n = this.niveis().find((x) => x.fontes.length === sel.size && x.fontes.every((f) => sel.has(f)));
    return n ? n.numero : null;
  });

  /** Atalhos do piloto: cada um já traz o filtro de título que evita comparar produto errado. */
  protected readonly sugestoes: { termo: string; criterios: Partial<CriteriosCotacao> }[] = [
    { termo: 'ssd 256gb', criterios: { deveConter: '256', segmentos: 'M.2, SATA' } },
    { termo: 'mouse sem fio logitech', criterios: { deveConter: 'logitech', segmentos: '' } },
    { termo: 'monitor 24 polegadas', criterios: { deveConter: '24', segmentos: '' } },
    { termo: 'teclado mecânico abnt2', criterios: { deveConter: '', segmentos: '' } },
  ];

  private atraso?: ReturnType<typeof setTimeout>;
  /** Número da última reavaliação pedida: resposta de uma anterior que chegue atrasada é descartada. */
  private seqReavaliacao = 0;

  constructor() {
    this.carregarEstado();
  }

  private async carregarEstado(): Promise<void> {
    try {
      const e = await firstValueFrom(this.api.estado());
      this.padrao.set(e.padrao);
      this.ocupado.set(e.ocupado);
      this.opcoesPaginas.set(Array.from({ length: e.maxPaginas }, (_, i) => i + 1));
      this.lojas.set(e.lojas);
      this.niveis.set(e.niveis);
      this.selecionadas.set(new Set(e.niveis[0]?.fontes ?? []));
    } catch (e) {
      this.erros.set(await mensagensDeErro(e));
    }
  }

  protected cotar(ev: Event): void {
    ev.preventDefault();
    const form = ev.target as HTMLFormElement;
    const termo = (form.elements.namedItem('termo') as HTMLInputElement).value.trim();
    const paginas = Number((form.elements.namedItem('paginas') as HTMLSelectElement).value) || 1;
    this.executar(termo, paginas);
  }

  protected escolherNivel(numero: number): void {
    const n = this.niveis().find((x) => x.numero === numero);
    if (n) this.selecionadas.set(new Set(n.fontes));
  }

  protected alternarLoja(id: string): void {
    this.selecionadas.update((s) => {
      const nova = new Set(s);
      if (nova.has(id)) nova.delete(id);
      else nova.add(id);
      return nova;
    });
  }

  /** Sugestão rápida: aplica o filtro de título dela e cota com 1 página. */
  protected testar(s: { termo: string; criterios: Partial<CriteriosCotacao> }): void {
    this.painel().sugerir(s.criterios);
    this.executar(s.termo, 1);
  }

  private async executar(termo: string, paginas: number): Promise<void> {
    if (!termo) {
      this.erros.set(['Informe o produto a cotar.']);
      return;
    }
    clearTimeout(this.atraso);
    this.seqReavaliacao++; // invalida reavaliação em voo: o resultado desta coleta é o que vale
    this.erros.set([]);
    this.cotando.set(true);
    this.ultimoTermo.set(termo);
    // ordem das lojas = a do servidor (a mesma dos chips), não a ordem em que foram clicadas
    const fontes = this.lojas().map((l) => l.id).filter((id) => this.selecionadas().has(id));
    const nomes = this.lojas().filter((l) => fontes.includes(l.id)).map((l) => l.nome);
    this.avisos.iniciar(this.ocupado() ? 'Aguardando outra cotação terminar'
      : `Buscando em ${nomes.length} loja${nomes.length > 1 ? 's' : ''}: ${nomes.join(', ')}`);
    try {
      this.resposta.set(await firstValueFrom(this.api.cotar(termo, paginas, fontes, this.painel().valor())));
    } catch (e) {
      this.erros.set(await mensagensDeErro(e));
    } finally {
      this.cotando.set(false);
      this.avisos.terminar();
      this.atualizarOcupado();
    }
  }

  /** Com resultado na tela, reavalia 350 ms depois da última mudança (arrastar um peso não dispara 20 chamadas). */
  protected criteriosMudaram(c: CriteriosCotacao): void {
    const r = this.resposta();
    if (!r || this.cotando()) return;
    clearTimeout(this.atraso);
    this.atraso = setTimeout(() => this.reavaliar(r.id, c), 350);
  }

  private async reavaliar(id: string, c: CriteriosCotacao): Promise<void> {
    const seq = ++this.seqReavaliacao;
    this.reavaliando.set(true);
    try {
      const nova = await firstValueFrom(this.api.reavaliar(id, c));
      if (seq === this.seqReavaliacao) this.resposta.set(nova);
    } catch (e) {
      if (seq === this.seqReavaliacao) this.erros.set(await mensagensDeErro(e));
    } finally {
      if (seq === this.seqReavaliacao) this.reavaliando.set(false);
    }
  }

  protected async escolher(r: RespostaCotacao, a: Avaliado): Promise<void> {
    const alternativas = alternativasPara(r.resultado, a);
    try {
      await this.cesta.adicionar(r.id, r.termo, a, alternativas);
      this.avisos.toast(`Item ${this.cesta.itens().length} no orçamento · tirando ${alternativas.length + 1} prints`);
    } catch (e) {
      this.avisos.toast(e instanceof Error ? e.message : (await mensagensDeErro(e)).join(' '), '⚠');
    }
  }

  protected async baixarPlanilha(): Promise<void> {
    const r = this.resposta();
    if (!r) return;
    try {
      salvarArquivo(await firstValueFrom(this.api.planilha(r.id)));
      this.avisos.toast('Planilha baixada');
    } catch (e) {
      this.avisos.toast((await mensagensDeErro(e)).join(' '), '⚠');
    }
  }

  private async atualizarOcupado(): Promise<void> {
    try {
      this.ocupado.set((await firstValueFrom(this.api.estado())).ocupado);
    } catch {
      // só o aviso de fila depende disso; sem ele a tela continua funcionando
    }
  }
}
