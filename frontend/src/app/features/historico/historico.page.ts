import { CurrencyPipe, DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, ElementRef, HostListener, computed, inject, signal, viewChild } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, ParamMap, Router } from '@angular/router';
import { Observable, firstValueFrom } from 'rxjs';
import { Api, mensagensDeErro, salvarArquivo } from '../../core/api';
import { Avisos } from '../../core/avisos';
import { fmtBRL, parseBRL } from '../../core/dinheiro';
import {
  FiltrosHistorico,
  Fornecedor,
  ItemHistorico,
  Loja,
  MODOS,
  ModoAquisicao,
  OrigemOrcamento,
  ROTULO_MODO,
  ResultadoImportacao,
} from '../../core/modelos';
import { Faixa } from '../../layout/faixa';
import { Icone } from '../../layout/icone';

/** Abas: todos, um por tipo e a lixeira (o que foi apagado nos últimos 30 dias). */
type Aba = ModoAquisicao | 'TODOS' | 'LIXEIRA';

const POR_PAGINA = 20;

/**
 * Histórico central (substitui o IndexedDB por navegador da v3.5), dividido em abas por tipo de requisição,
 * com a contagem de cada uma e paginação: o volume passou de uma centena e a lista única não dava conta.
 *
 * A aba e a página ficam na URL (/historico?tipo=OPEX&pagina=2): dá para mandar o link e o "voltar" do
 * navegador funciona. A busca vale para todas as abas, e os números das abas acompanham o que foi buscado.
 *
 * Filtros (30/09/2026): período de emissão, loja, fornecedor, faixa de valor e origem, num painel que abre pelo botão
 * "Filtros". Também ficam na URL (?de=2026-09-01&loja=23...) e valem para a lista e para os números das abas.
 *
 * Apagar manda para a aba Lixeira (30/09/2026): de lá o orçamento é restaurado ou excluído de vez; passados
 * 30 dias, o servidor apaga sozinho (LixeiraHistorico).
 */
@Component({
  selector: 'ha-historico',
  imports: [DatePipe, CurrencyPipe, Faixa, Icone],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <ha-faixa sobretitulo="Helpdesk · Registro" titulo="Histórico" subtitulo="Todos os orçamentos gerados pelo helpdesk" />
    <main class="container">
    <section class="card">
      <header class="card-header">
        <h2>Orçamentos gerados</h2>
        <span class="card-sub">{{ contagem()['TODOS'] ?? 0 }} no total</span>
      </header>
      <div class="card-body">
        <div class="hist-toolbar">
          <input class="busca" #b placeholder="Buscar por loja, chamado, título...  ( / )" [value]="termo()" (input)="buscar(b.value)"
            title="Atalho: / em qualquer lugar da tela">
          <button class="btn-acao" [class.ativo]="painelAberto()" (click)="painelAberto.set(!painelAberto())"
            [attr.aria-expanded]="painelAberto()" title="Filtrar por período, loja, valor e origem">
            Filtros @if (filtrosAtivos()) { <span class="nav-contador">{{ filtrosAtivos() }}</span> }
          </button>
          <button class="btn-acao" (click)="exportar()" [disabled]="ocupado() || !contagem()['TODOS']"
            title="Baixa um JSON com todos os orçamentos e seus PDFs">
            <ha-icone nome="baixar" [tamanho]="15" /> Exportar JSON
          </button>
          <button class="btn-acao" (click)="arquivo.click()" [disabled]="ocupado()"
            title="Adiciona ao histórico os registros de um backup (deste sistema ou do HTML v3.5)">
            <ha-icone nome="enviarArquivo" [tamanho]="15" /> Importar JSON
          </button>
          <input #arquivo type="file" accept="application/json,.json" hidden (change)="importar(arquivo)">
        </div>

        @if (painelAberto()) {
          <div class="hist-filtros">
            <label>Emissão de <input type="date" [value]="filtros().de ?? ''" (change)="filtrar('de', $any($event.target).value)"></label>
            <label>até <input type="date" [value]="filtros().ate ?? ''" (change)="filtrar('ate', $any($event.target).value)"></label>
            <label>Loja
              <select [value]="filtros().loja ?? ''" (change)="filtrar('loja', $any($event.target).value)">
                <option value="">Todas</option>
                @for (l of lojas(); track l.numero) { <option [value]="l.numero">{{ l.numero }} · {{ l.nome }}</option> }
              </select>
            </label>
            <label>Valor de (R$) <input inputmode="decimal" placeholder="0,00" [value]="valorTexto(filtros().valorMin)"
              (change)="filtrar('valorMin', $any($event.target).value)"></label>
            <label>até (R$) <input inputmode="decimal" placeholder="sem limite" [value]="valorTexto(filtros().valorMax)"
              (change)="filtrar('valorMax', $any($event.target).value)"></label>
            <label>Fornecedor
              <select [value]="filtros().fornecedor ?? ''" (change)="filtrar('fornecedor', $any($event.target).value)">
                <option value="">Todos</option>
                @for (f of fornecedores(); track f.id) { <option [value]="f.id">{{ f.nome }}</option> }
              </select>
            </label>
            <label>Origem
              <select [value]="filtros().origem ?? ''" (change)="filtrar('origem', $any($event.target).value)">
                <option value="">Todas</option>
                <option value="DOCUMENTOS">Documentos (fluxo normal)</option>
                <option value="COTACAO">Por cotação</option>
              </select>
            </label>
            <button class="btn-link" type="button" (click)="limparFiltros()" [disabled]="!filtrosAtivos()">Limpar filtros</button>
          </div>
        }

        <div class="abas-tipo" role="tablist" aria-label="Tipo de requisição">
          @for (a of abas; track a) {
            <button role="tab" [attr.aria-selected]="aba() === a" [class.ativo]="aba() === a" [class.aba-lixeira]="a === 'LIXEIRA'"
              (click)="irPara(a, 0)">
              @if (a === 'LIXEIRA') { <ha-icone nome="fechar" [tamanho]="13" /> }
              {{ rotulo(a) }} <span class="contagem">{{ contagem()[a] ?? 0 }}</span>
            </button>
          }
        </div>
        @if (naLixeira()) {
          <div class="status alerta">Apagados nos últimos 30 dias. Restaure o que foi por engano; depois de 30 dias o
            orçamento e o PDF são excluídos de vez.</div>
        }

        @if (resultado(); as r) {
          <div class="status" [class.ok]="!r.problemas.length" [class.erro]="r.problemas.length">
            <b>Importação:</b> {{ r.importados }} importado(s), {{ r.ignorados }} já existia(m).
            @if (r.problemas.length) {
              <ul>@for (p of r.problemas; track $index) { <li>{{ p }}</li> }</ul>
            }
          </div>
        }
        @if (erro()) { <div class="status erro">{{ erro() }}</div> }
        <div class="hist-lista" [class.carregando]="carregando()">
          @for (r of itens(); track r.id) {
            <div class="hist-item">
              <div class="hist-data">{{ r.criadoEm | date: 'dd/MM/yyyy HH:mm' }}</div>
              <div>
                <div class="hist-titulo">{{ r.titulo }}</div>
                <div class="hist-meta">
                  @if (aba() === 'TODOS') { <span class="selo-tipo selo-{{ r.modo }}">{{ rotulo(r.modo) }}</span> }
                  @if (r.origem === 'COTACAO') { <span class="selo-tipo selo-COTACAO" title="Montado pela cotação em lojas online, com os prints anexados">por cotação</span> }
                  Loja {{ r.lojaNumero }} · {{ r.lojaNome }} · Chamado {{ r.chamadoNum || '—' }}@if (r.fornecedores.length) { · {{ r.fornecedores.join(', ') }} }@if (r.criadoPor !== 'anonimo') { · por {{ r.criadoPor }} }
                </div>
                @if (r.excluidoEm) {
                  <div class="hist-meta hist-excluido">Apagado em {{ r.excluidoEm | date: 'dd/MM/yyyy HH:mm' }} por {{ r.excluidoPor }}</div>
                }
              </div>
              <div class="hist-total">{{ r.total | currency: 'BRL' }}</div>
              <div class="hist-acoes">
                <button (click)="baixar(r)" title="Baixar PDF novamente" aria-label="Baixar PDF"><ha-icone nome="baixar" [tamanho]="16" /></button>
                @if (r.excluidoEm) {
                  <button (click)="restaurar(r)" title="Restaurar para o histórico" aria-label="Restaurar"><ha-icone nome="historico" [tamanho]="16" /></button>
                  <button class="perigo" (click)="excluirDeVez(r)" title="Excluir de vez (orçamento e PDF)" aria-label="Excluir de vez"><ha-icone nome="fechar" [tamanho]="16" /></button>
                } @else {
                  <button class="perigo" (click)="remover(r)" title="Mandar para a lixeira" aria-label="Apagar"><ha-icone nome="fechar" [tamanho]="16" /></button>
                }
              </div>
            </div>
          } @empty {
            <div class="hist-vazio">
              {{ filtrosAtivos() && !termo() ? 'Nenhum orçamento com estes filtros.'
                : termo() ? 'Nenhum resultado para "' + termo() + '"' + (aba() !== 'TODOS' ? ' em ' + rotulo(aba()) : '') + '.'
                : naLixeira() ? 'A lixeira está vazia.'
                : aba() === 'TODOS' ? 'Nenhum orçamento gerado ainda.' : 'Nenhum orçamento de ' + rotulo(aba()) + ' ainda.' }}
            </div>
          }
        </div>

        @if (totalPaginas() > 1) {
          <nav class="paginacao" aria-label="Páginas">
            <button (click)="irPara(aba(), pagina() - 1)" [disabled]="pagina() === 0">‹ Anterior</button>
            <span>{{ primeiro() }}–{{ ultimo() }} de {{ totalAba() }} · página {{ pagina() + 1 }} de {{ totalPaginas() }}</span>
            <button (click)="irPara(aba(), pagina() + 1)" [disabled]="pagina() + 1 >= totalPaginas()">Próxima ›</button>
          </nav>
        }
      </div>
    </section>
    </main>
  `,
})
export class HistoricoPage {
  private readonly api = inject(Api);
  private readonly avisos = inject(Avisos);
  private readonly router = inject(Router);
  private readonly rota = inject(ActivatedRoute);

  protected readonly abas: Aba[] = ['TODOS', ...MODOS, 'LIXEIRA'];

  protected readonly itens = signal<ItemHistorico[]>([]);
  protected readonly contagem = signal<Partial<Record<Aba, number>>>({});
  protected readonly aba = signal<Aba>('TODOS');
  protected readonly pagina = signal(0);
  protected readonly totalAba = signal(0);
  protected readonly termo = signal('');
  protected readonly erro = signal<string | null>(null);
  protected readonly ocupado = signal(false);
  protected readonly carregando = signal(false);
  protected readonly resultado = signal<ResultadoImportacao | null>(null);
  private atraso?: ReturnType<typeof setTimeout>;

  protected readonly naLixeira = computed(() => this.aba() === 'LIXEIRA');
  protected readonly filtros = signal<FiltrosHistorico>({});
  protected readonly painelAberto = signal(false);
  protected readonly filtrosAtivos = computed(() => Object.values(this.filtros()).filter((v) => v !== undefined).length);
  /** Todas as lojas, inclusive desativadas: orçamento antigo pode ser de loja que fechou. */
  protected readonly lojas = toSignal(this.api.lojasTodas(), { initialValue: [] as Loja[] });
  /** Todos os fornecedores, inclusive desativados (orçamento antigo). */
  protected readonly fornecedores = toSignal(this.api.fornecedoresTodos(), { initialValue: [] as Fornecedor[] });
  protected readonly totalPaginas = computed(() => Math.max(1, Math.ceil(this.totalAba() / POR_PAGINA)));
  protected readonly primeiro = computed(() => (this.totalAba() ? this.pagina() * POR_PAGINA + 1 : 0));
  protected readonly ultimo = computed(() => Math.min(this.totalAba(), (this.pagina() + 1) * POR_PAGINA));

  constructor() {
    // A URL manda: abrir um link /historico?tipo=CAPEX&pagina=2 cai direto na aba e na página certas.
    this.rota.queryParamMap.subscribe((q) => {
      const tipo = q.get('tipo') as Aba | null;
      this.aba.set(tipo && this.abas.includes(tipo) ? tipo : 'TODOS');
      this.pagina.set(Math.max(0, (Number(q.get('pagina')) || 1) - 1));
      this.termo.set(q.get('busca') ?? '');
      this.filtros.set(filtrosDaUrl(q));
      if (this.filtrosAtivos()) this.painelAberto.set(true);
      this.carregar();
    });
  }

  private readonly campoBusca = viewChild<ElementRef<HTMLInputElement>>('b');

  /** "/" fora de um campo leva à busca (atalho de 30/09/2026, o mesmo de muitos sistemas). */
  @HostListener('document:keydown', ['$event'])
  protected atalho(ev: KeyboardEvent): void {
    const alvo = ev.target as HTMLElement | null;
    const digitando = !!alvo && (alvo.isContentEditable || ['INPUT', 'TEXTAREA', 'SELECT'].includes(alvo.tagName));
    if (ev.key === '/' && !digitando && !ev.ctrlKey && !ev.metaKey && !ev.altKey) {
      ev.preventDefault();
      this.campoBusca()?.nativeElement.focus();
    }
  }

  protected rotulo(a: Aba): string {
    return a === 'TODOS' ? 'Todos' : a === 'LIXEIRA' ? 'Lixeira' : ROTULO_MODO[a];
  }

  /** Muda aba/página pela URL (o carregamento acontece na assinatura do construtor). */
  protected irPara(aba: Aba, pagina: number): void {
    this.router.navigate([], {
      relativeTo: this.rota,
      queryParams: {
        tipo: aba === 'TODOS' ? null : aba,
        pagina: pagina > 0 ? pagina + 1 : null,
        busca: this.termo() || null,
        de: this.filtros().de ?? null,
        ate: this.filtros().ate ?? null,
        loja: this.filtros().loja ?? null,
        valorMin: this.filtros().valorMin ?? null,
        valorMax: this.filtros().valorMax ?? null,
        origem: this.filtros().origem ?? null,
        fornecedor: this.filtros().fornecedor ?? null,
      },
    });
  }

  /** Muda um filtro e volta para a primeira página (via URL, como as abas). */
  protected filtrar(campo: keyof FiltrosHistorico, bruto: string): void {
    const f = { ...this.filtros() };
    const texto = bruto.trim();
    if (!texto) delete f[campo];
    else if (campo === 'loja' || campo === 'fornecedor') f[campo] = Number(texto);
    else if (campo === 'valorMin' || campo === 'valorMax') {
      const v = parseBRL(texto);
      if (v > 0) f[campo] = v;
      else delete f[campo];
    } else if (campo === 'origem') f.origem = texto as OrigemOrcamento;
    else f[campo] = texto;
    this.filtros.set(f);
    this.irPara(this.aba(), 0);
  }

  protected limparFiltros(): void {
    this.filtros.set({});
    this.irPara(this.aba(), 0);
  }

  protected valorTexto(v: number | undefined): string {
    return v === undefined ? '' : fmtBRL(v);
  }

  protected buscar(t: string): void {
    this.termo.set(t);
    clearTimeout(this.atraso);
    // Nova busca volta para a primeira página da aba atual.
    this.atraso = setTimeout(() => this.irPara(this.aba(), 0), 300);
  }

  private async carregar(): Promise<void> {
    this.carregando.set(true);
    try {
      const aba = this.aba();
      const [p, c] = await Promise.all([
        firstValueFrom(this.api.historico(this.termo(), this.pagina(), aba === 'TODOS' || aba === 'LIXEIRA' ? null : aba,
          POR_PAGINA, aba === 'LIXEIRA', this.filtros())),
        firstValueFrom(this.api.contagemHistorico(this.termo(), this.filtros())),
      ]);
      this.itens.set(p.itens);
      this.totalAba.set(p.total);
      this.contagem.set(c);
      this.erro.set(null);
    } catch (e) {
      this.erro.set((await mensagensDeErro(e)).join(' '));
    } finally {
      this.carregando.set(false);
    }
  }

  protected async baixar(r: ItemHistorico): Promise<void> {
    try {
      salvarArquivo(await firstValueFrom(this.api.baixar(r.id)));
    } catch (e) {
      this.avisos.toast((await mensagensDeErro(e)).join(' '), '⚠');
    }
  }

  protected async exportar(): Promise<void> {
    this.ocupado.set(true);
    this.avisos.iniciar('Montando o backup do histórico');
    try {
      salvarArquivo(await firstValueFrom(this.api.exportarHistorico()));
      this.avisos.toast('Backup do histórico baixado');
    } catch (e) {
      this.avisos.toast((await mensagensDeErro(e)).join(' '), '⚠');
    } finally {
      this.ocupado.set(false);
      this.avisos.terminar();
    }
  }

  protected async importar(input: HTMLInputElement): Promise<void> {
    const f = input.files?.[0];
    input.value = ''; // permite importar o mesmo arquivo de novo
    if (!f || !confirm(`Importar "${f.name}"? Os registros serão ADICIONADOS ao histórico (repetidos são ignorados).`)) return;
    this.ocupado.set(true);
    this.resultado.set(null);
    this.avisos.iniciar('Importando o histórico');
    try {
      this.resultado.set(await firstValueFrom(this.api.importarHistorico(f)));
      await this.carregar();
    } catch (e) {
      this.erro.set((await mensagensDeErro(e)).join(' '));
    } finally {
      this.ocupado.set(false);
      this.avisos.terminar();
    }
  }

  protected async remover(r: ItemHistorico): Promise<void> {
    if (!confirm(`Mandar "${r.titulo}" para a lixeira? Dá para restaurar por 30 dias.`)) return;
    await this.acao(this.api.remover(r.id), 'Enviado para a lixeira');
  }

  protected async restaurar(r: ItemHistorico): Promise<void> {
    await this.acao(this.api.restaurar(r.id), 'Restaurado para o histórico');
  }

  protected async excluirDeVez(r: ItemHistorico): Promise<void> {
    if (!confirm(`Excluir "${r.titulo}" DE VEZ? O orçamento e o PDF somem e não há como recuperar.`)) return;
    await this.acao(this.api.excluirDeVez(r.id), 'Excluído de vez');
  }

  private async acao(chamada: Observable<void>, mensagem: string): Promise<void> {
    try {
      await firstValueFrom(chamada);
      this.avisos.toast(mensagem);
      await this.carregar();
    } catch (e) {
      this.avisos.toast((await mensagensDeErro(e)).join(' '), '⚠');
    }
  }
}

/** Filtros a partir da URL (?de=&ate=&loja=&valorMin=&valorMax=&origem=); valor inválido é ignorado. */
function filtrosDaUrl(q: ParamMap): FiltrosHistorico {
  const f: FiltrosHistorico = {};
  const data = (v: string | null) => (v && /^\d{4}-\d{2}-\d{2}$/.test(v) ? v : undefined);
  const numero = (v: string | null) => (v && Number(v) > 0 ? Number(v) : undefined);
  f.de = data(q.get('de'));
  f.ate = data(q.get('ate'));
  f.loja = numero(q.get('loja'));
  f.fornecedor = numero(q.get('fornecedor'));
  f.valorMin = numero(q.get('valorMin'));
  f.valorMax = numero(q.get('valorMax'));
  const origem = q.get('origem');
  f.origem = origem === 'DOCUMENTOS' || origem === 'COTACAO' ? origem : undefined;
  for (const k of Object.keys(f) as (keyof FiltrosHistorico)[]) if (f[k] === undefined) delete f[k];
  return f;
}
