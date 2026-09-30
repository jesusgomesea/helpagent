import { CurrencyPipe, DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { Observable, firstValueFrom } from 'rxjs';
import { Api, mensagensDeErro, salvarArquivo } from '../../core/api';
import { Avisos } from '../../core/avisos';
import { ItemHistorico, MODOS, ModoAquisicao, ROTULO_MODO, ResultadoImportacao } from '../../core/modelos';
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
          <input class="busca" #b placeholder="Buscar por loja, chamado, título..." [value]="termo()" (input)="buscar(b.value)">
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
                  Loja {{ r.lojaNumero }} · {{ r.lojaNome }} · Chamado {{ r.chamadoNum || '—' }}@if (r.criadoPor !== 'anonimo') { · por {{ r.criadoPor }} }
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
              {{ termo() ? 'Nenhum resultado para "' + termo() + '"' + (aba() !== 'TODOS' ? ' em ' + rotulo(aba()) : '') + '.'
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
      this.carregar();
    });
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
      },
    });
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
          POR_PAGINA, aba === 'LIXEIRA')),
        firstValueFrom(this.api.contagemHistorico(this.termo())),
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
