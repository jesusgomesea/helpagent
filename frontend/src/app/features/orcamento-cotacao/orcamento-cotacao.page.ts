import { CurrencyPipe, DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { Api, mensagensDeErro, salvarArquivo } from '../../core/api';
import { criarAnexo, liberarAnexo } from '../../core/arquivos';
import { Avisos } from '../../core/avisos';
import { dataLocalISO, fmtBRL, numeroOuNulo, parseBRL } from '../../core/dinheiro';
import { Anexo, DICA_MODO, Loja, MODOS, ModoAquisicao, ROTULO_MODO, exigeChamado } from '../../core/modelos';
import { ImagensApi, SrcApi } from '../../core/imagem-api';
import { Faixa } from '../../layout/faixa';
import { CestaCotacao, ItemCesta, MAX_ITENS_CESTA, OpcaoCesta } from '../cotacao/cesta.store';
import { CotacaoApi } from '../cotacao/cotacao.api';

/**
 * Orçamento por cotação: revisa a cesta montada na tela Cotação (itens, quantidades e os 3 prints de cada um),
 * completa os dados do impresso (loja, chamado, responsáveis) e gera — o PDF sai com o resumo da cotação e os
 * prints anexados, e fica guardado no histórico como qualquer outro orçamento (POST /api/orcamentos).
 *
 * Toda conferência que vale é do servidor (A1, prints prontos); aqui só se mostra e se evita clicar à toa.
 */
@Component({
  selector: 'ha-orcamento-cotacao',
  imports: [Faixa, FormsModule, RouterLink, CurrencyPipe, DatePipe, SrcApi],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <ha-faixa sobretitulo="Compras · Orçamento por cotação" titulo="Orçamento por cotação"
      subtitulo="Os itens escolhidos na cotação, com o print das 3 opções de cada um para a validação. Complete os dados e gere: o PDF fica guardado no histórico." />
    <main class="container largo">
      @if (!cesta.itens().length && !sucesso()) {
        <div class="status alerta">
          <b>Nenhum item escolhido ainda.</b> Na tela <a class="btn-link" routerLink="/cotacao">Cotação</a>, pesquise o item e
          clique em "Escolher para o orçamento" no anúncio que vai para o impresso.
        </div>
      }

      @if (cesta.itens().length) {
        <section class="card">
          <header class="card-header">
            <span class="card-num">1</span>
            <h2>Itens cotados</h2>
            <span class="card-sub">{{ cesta.itens().length }}/{{ maxItens() }} · a escolhida entra no impresso; as outras 2 vão como comparação</span>
          </header>
          <div class="card-body">
            @for (item of cesta.itens(); track item.id; let i = $index) {
              <article class="oc-item">
                <div class="oc-item-cab">
                  <span class="item-ord">{{ ordem(i) }}</span>
                  <label>Produto (como sai no impresso)
                    <input [ngModel]="item.produto" (ngModelChange)="cesta.atualizar(item.id, { produto: $event })" maxlength="200">
                  </label>
                  <label>Descrição
                    <input [ngModel]="item.descricao" (ngModelChange)="cesta.atualizar(item.id, { descricao: $event })" maxlength="500">
                  </label>
                  <label class="oc-qtd">Qtd
                    <input type="number" min="1" [ngModel]="item.quantidade" (ngModelChange)="cesta.atualizar(item.id, { quantidade: +$event || 1 })">
                  </label>
                  <label class="oc-unit">Unitário
                    <input [ngModel]="fmt(item.valorUnitario)" (change)="alterarUnitario(item, $event)">
                  </label>
                  <span class="oc-total">{{ item.quantidade * item.valorUnitario | currency: 'BRL' }}</span>
                  <button type="button" class="btn-rm" (click)="cesta.remover(item.id)" title="Tirar da cesta">×</button>
                </div>
                @if (precoMudou(item)) {
                  <div class="oc-nota">O unitário difere do preço coletado ({{ item.opcoes[0].preco | currency: 'BRL' }}) — o resumo do PDF mostra o coletado.</div>
                }
                <div class="oc-opcoes">
                  @for (o of item.opcoes; track o.printId; let j = $index) {
                    <figure class="oc-opcao" [class.oc-escolhida]="j === 0" [class.oc-falhou]="o.situacao === 'FALHOU'"
                      [class.oc-alerta]="o.situacao === 'PRONTO' && o.alerta">
                      <figcaption>
                        <span class="oc-rotulo">{{ j === 0 ? 'Escolhida' : 'Opção ' + (j + 1) }}</span>
                        <b>{{ o.fonte }}</b>
                        <span class="mono">{{ o.preco | currency: 'BRL' }}</span>
                      </figcaption>
                      @if (o.situacao === 'PRONTO' || o.situacao === 'MANUAL') {
                        <a class="oc-thumb" href="#" (click)="abrirImagem($event, o)" title="Abrir o print em tamanho real">
                          <img [haSrcApi]="imagem(o)" [alt]="'Print ' + o.fonte">
                        </a>
                      } @else if (o.situacao === 'CAPTURANDO') {
                        <div class="oc-thumb oc-espera"><span class="oc-girando"></span>tirando o print…</div>
                      } @else {
                        <div class="oc-thumb oc-erro">Não saiu: {{ o.motivo }}</div>
                      }
                      @if (o.situacao === 'PRONTO' && o.alerta) {
                        <p class="oc-aviso-print" title="Abra o print e confira se o preço aparece; senão, tire de novo ou anexe o seu">⚠ {{ o.alerta }}</p>
                      }
                      <p class="oc-titulo" [title]="o.titulo">{{ o.titulo }}</p>
                      <div class="oc-acoes">
                        <a [href]="o.url" target="_blank" rel="noopener">ver na loja</a>
                        @if (o.situacao !== 'CAPTURANDO') {
                          <button type="button" class="btn-link" (click)="recapturar(o)">tirar de novo</button>
                        }
                        <label class="btn-link oc-anexar" title="Use o seu print se a loja bloqueou o robô ou a página saiu diferente">
                          anexar print
                          <input type="file" accept="image/png,image/jpeg" (change)="anexar(o, $event)" hidden>
                        </label>
                      </div>
                      @if (o.capturadoEm) {
                        <span class="oc-quando">{{ o.situacao === 'MANUAL' ? 'anexado' : 'capturado' }} {{ o.capturadoEm | date: 'dd/MM HH:mm' }}</span>
                      }
                    </figure>
                  }
                </div>
              </article>
            }
            @if (cesta.itens().length < maxItens()) {
              <a class="btn-add" routerLink="/cotacao">+ Cotar outro item</a>
            }
          </div>
        </section>

        <section class="card">
          <header class="card-header">
            <span class="card-num">2</span>
            <h2>Dados do orçamento</h2>
            <span class="card-sub">loja, chamado e responsáveis</span>
          </header>
          <div class="card-body">
            <div class="modo">
              <span class="rotulo-secao">Tipo de requisição</span>
              <div class="seg" role="group" aria-label="Tipo de requisição">
                @for (m of modos; track m) {
                  <button type="button" [class.ativo]="modo() === m" (click)="modo.set(m)" [title]="dicas[m]">{{ rotulos[m] }}</button>
                }
              </div>
            </div>
            <div class="linha cols-2">
              <label>Número da loja
                <input [ngModel]="lojaNumero()" (ngModelChange)="aoDigitarNumero($event)" placeholder="ex: 23">
              </label>
              <label>Busca por nome
                <input [ngModel]="lojaBusca()" (ngModelChange)="aoBuscarNome($event)" placeholder="ex: DAMASIO PE" list="dl-lojas-oc">
                <datalist id="dl-lojas-oc">@for (l of lojas() ?? []; track l.numero) { <option [value]="l.nome"></option> }</datalist>
              </label>
            </div>
            @if (loja(); as l) {
              <div class="loja-tag">
                <span class="loja-num">{{ l.numero }}</span>
                <span class="loja-nome">{{ l.nome }}</span>
                <span class="loja-cnpj">{{ l.cnpj }}</span>
                <span class="tmpl tmpl-{{ l.template }}">{{ l.templateRotulo }}</span>
              </div>
            }
            <label>Título do orçamento
              <input [ngModel]="titulo()" (ngModelChange)="titulo.set($event)" maxlength="200" placeholder="ex: Aquisição de SSD e mouse">
            </label>
            <div class="linha cols-2">
              <label>Data de emissão <input type="date" [ngModel]="dataEmissao()" (ngModelChange)="dataEmissao.set($event)"></label>
              <label>Válido até <input type="date" [ngModel]="validade()" (ngModelChange)="validade.set($event)" [min]="dataEmissao()">
                <span class="ajuda">Opcional — preço de loja online não tem validade.</span>
              </label>
            </div>
            @if (exigeChamado()) {
              <label>Chamado(s) <input [ngModel]="chamadoNum()" (ngModelChange)="chamadoNum.set($event)" placeholder="ex: 1021069"></label>
            }
            <div class="oc-chamados">
              <span class="rotulo-secao">Print do chamado (opcional, vai anexado antes do resumo)</span>
              <div class="anexos">
                @for (c of chamados(); track c.id) {
                  <div class="chip">
                    <span class="chip-thumb">@if (c.tipo === 'imagem') { <img [src]="c.previewUrl" alt=""> } @else { PDF }</span>
                    <span class="chip-name">{{ c.nome }}</span>
                    <button type="button" class="chip-x" (click)="removerChamado(c)" title="Remover">×</button>
                  </div>
                }
                <label class="btn-add">+ anexar chamado
                  <input type="file" accept="image/*,application/pdf" multiple (change)="anexarChamado($event)" hidden>
                </label>
              </div>
            </div>
            <div class="totais">
              <label>Frete <input [ngModel]="frete()" (ngModelChange)="frete.set($event)" placeholder="em branco"></label>
              <label>Acréscimos <input [ngModel]="acrescimos()" (ngModelChange)="acrescimos.set($event)" placeholder="em branco"></label>
              <span></span>
              <label class="destaque">Total geral <input [value]="fmt(totalGeral())" readonly></label>
            </div>
            <div class="linha cols-2">
              <label>Requerente <input [ngModel]="requerente()" (ngModelChange)="requerente.set($event)"></label>
              <label>Gestor <input [ngModel]="gestor()" (ngModelChange)="gestor.set($event)"></label>
            </div>
            <label>Observações
              <textarea rows="3" [ngModel]="observacoes()" (ngModelChange)="observacoes.set($event)"
                placeholder="# [número do chamado]. Aquisição de… — cotação em lojas online, prints anexos"></textarea>
            </label>
          </div>
        </section>

        <section class="card">
          <header class="card-header">
            <span class="card-num">3</span>
            <h2>Gerar e guardar</h2>
            <span class="card-sub">impresso + resumo da cotação + prints</span>
          </header>
          <div class="card-body">
            @if (cesta.capturando()) {
              <div class="status alerta"><b>Aguarde os prints:</b> {{ cesta.capturando() }} ainda sendo tirado{{ cesta.capturando() > 1 ? 's' : '' }} (≈15 s por item).</div>
            }
            @if (cesta.alertas()) {
              <div class="status alerta"><b>{{ cesta.alertas() }} print{{ cesta.alertas() > 1 ? 's' : '' }} para conferir:</b> a conferência automática não achou o preço coletado visível. Abra a imagem; se estiver certa, pode gerar (o resumo do PDF marca "conferir print"), senão tire de novo ou anexe o seu.</div>
            }
            @if (cesta.falhas()) {
              <div class="status erro"><b>{{ cesta.falhas() }} print{{ cesta.falhas() > 1 ? 's' : '' }} não saiu.</b> Tire de novo, anexe o seu print ou tire o item da cesta.</div>
            }
            <button class="btn-primario" type="button" [disabled]="gerando() || !cesta.pronta()" (click)="gerar()">
              ↓ Gerar e guardar o orçamento
            </button>
            @if (erros().length) {
              <div class="status erro"><ul>@for (e of erros(); track $index) { <li>{{ e }}</li> }</ul></div>
            }
          </div>
        </section>
      }

      @if (sucesso(); as s) {
        <div class="status ok">{{ s }}</div>
        <a class="btn-secundario" routerLink="/cotacao">↺ Novo orçamento por cotação</a>
      }
    </main>
  `,
})
export class OrcamentoCotacaoPage {
  protected readonly cesta = inject(CestaCotacao);
  private readonly api = inject(Api);
  private readonly cotacaoApi = inject(CotacaoApi);
  private readonly imagens = inject(ImagensApi);
  private readonly avisos = inject(Avisos);

  protected readonly modos = MODOS;
  protected readonly rotulos = ROTULO_MODO;
  protected readonly dicas = DICA_MODO;
  protected readonly fmt = fmtBRL;

  protected readonly lojas = toSignal(this.api.lojas());
  private readonly parametros = toSignal(this.api.parametros());
  /** O limite do servidor manda; 10 (linhas do impresso) só até ele responder. */
  protected readonly maxItens = computed(() => this.parametros()?.maxItens ?? MAX_ITENS_CESTA);

  protected readonly modo = signal<ModoAquisicao>('REQUISICAO');
  protected readonly exigeChamado = computed(() => exigeChamado(this.modo()));
  protected readonly loja = signal<Loja | null>(null);
  protected readonly lojaNumero = signal('');
  protected readonly lojaBusca = signal('');
  protected readonly titulo = signal('');
  protected readonly dataEmissao = signal(dataLocalISO());
  protected readonly validade = signal('');
  protected readonly chamadoNum = signal('');
  protected readonly chamados = signal<Anexo[]>([]);
  protected readonly frete = signal('');
  protected readonly acrescimos = signal('');
  protected readonly requerente = signal('');
  protected readonly gestor = signal('');
  protected readonly observacoes = signal('');

  protected readonly gerando = signal(false);
  protected readonly erros = signal<string[]>([]);
  protected readonly sucesso = signal<string | null>(null);

  protected readonly totalGeral = computed(() => this.cesta.total() + parseBRL(this.frete()) + parseBRL(this.acrescimos()));

  constructor() {
    // os responsáveis padrão chegam depois da tela: preenche só o que o atendente ainda não digitou
    effect(() => {
      const p = this.parametros();
      if (!p) return;
      untracked(() => {
        if (!this.requerente()) this.requerente.set(p.requerentePadrao);
        if (!this.gestor()) this.gestor.set(p.gestorPadrao);
      });
    });
  }

  protected ordem(i: number): string {
    return (i + 1).toString().padStart(2, '0');
  }

  /** Caminho /api da imagem; carregada pela API (SrcApi) para funcionar com login e apiBase. */
  protected imagem(o: OpcaoCesta): string {
    return this.cotacaoApi.urlImagem(o.printId, o.capturadoEm);
  }

  protected abrirImagem(ev: Event, o: OpcaoCesta): void {
    ev.preventDefault();
    this.imagens.abrir(this.imagem(o));
  }

  protected precoMudou(item: ItemCesta): boolean {
    const coletado = item.opcoes[0]?.preco;
    return coletado !== null && coletado !== undefined && Math.abs(coletado - item.valorUnitario) > 0.004;
  }

  protected alterarUnitario(item: ItemCesta, ev: Event): void {
    const v = parseBRL((ev.target as HTMLInputElement).value);
    if (v > 0) this.cesta.atualizar(item.id, { valorUnitario: v });
    (ev.target as HTMLInputElement).value = fmtBRL(v > 0 ? v : item.valorUnitario);
  }

  protected async recapturar(o: OpcaoCesta): Promise<void> {
    try {
      await this.cesta.recapturar(o.printId);
    } catch (e) {
      this.avisos.toast((await mensagensDeErro(e)).join(' '), '⚠');
    }
  }

  protected async anexar(o: OpcaoCesta, ev: Event): Promise<void> {
    const input = ev.target as HTMLInputElement;
    const arq = input.files?.[0];
    input.value = '';
    if (!arq) return;
    try {
      await this.cesta.anexarManual(o.printId, arq, arq.name);
      this.avisos.toast(`Print da ${o.fonte} substituído`);
    } catch (e) {
      this.avisos.toast((await mensagensDeErro(e)).join(' '), '⚠');
    }
  }

  protected async anexarChamado(ev: Event): Promise<void> {
    const input = ev.target as HTMLInputElement;
    const arquivos = Array.from(input.files ?? []);
    input.value = '';
    for (const f of arquivos) {
      try {
        const a = await criarAnexo(f, f.name);
        this.chamados.update((l) => [...l, a]);
      } catch (e) {
        this.avisos.toast(e instanceof Error ? e.message : String(e), '⚠');
      }
    }
  }

  protected removerChamado(c: Anexo): void {
    liberarAnexo(c);
    this.chamados.update((l) => l.filter((x) => x.id !== c.id));
  }

  /** "023" e "23" são a mesma loja (igual à revisão do fluxo por documentos). */
  protected aoDigitarNumero(v: string): void {
    this.lojaNumero.set(v);
    const n = parseInt(v.trim(), 10);
    const l = Number.isNaN(n) ? null : ((this.lojas() ?? []).find((x) => x.numero === n) ?? null);
    this.loja.set(l);
    if (l) this.lojaBusca.set(l.nome);
  }

  protected aoBuscarNome(v: string): void {
    this.lojaBusca.set(v);
    const termo = v.trim().toLowerCase();
    if (termo.length < 2) return;
    const l = (this.lojas() ?? []).find((x) => x.nome.toLowerCase().includes(termo));
    if (l) {
      this.loja.set(l);
      this.lojaNumero.set(String(l.numero));
    }
  }

  protected async gerar(): Promise<void> {
    this.erros.set([]);
    const itens = this.cesta.itens();
    if (itens.length > this.maxItens()) {
      this.erros.set([`O impresso aceita até ${this.maxItens()} itens.`]);
      return;
    }
    this.gerando.set(true);
    this.avisos.iniciar('Montando o impresso com o resumo e os prints da cotação');
    try {
      const pdf = await firstValueFrom(
        this.api.gerar(
          {
            modo: this.modo(),
            lojaNumero: this.loja() ? String(this.loja()!.numero) : this.lojaNumero(),
            titulo: this.titulo(),
            dataEmissao: this.dataEmissao(),
            validade: this.validade() || null,
            chamadoNum: this.exigeChamado() ? this.chamadoNum() || null : null,
            itens: itens.map((i) => ({
              produto: i.produto,
              descricao: i.descricao,
              quantidade: i.quantidade,
              valorUnitario: i.valorUnitario,
              prints: i.opcoes.map((o) => o.printId),
            })),
            subtotal: null,
            frete: numeroOuNulo(this.frete()),
            acrescimos: numeroOuNulo(this.acrescimos()),
            observacoes: this.observacoes(),
            requerente: this.requerente(),
            gestor: this.gestor(),
          },
          this.chamados(),
          [],
        ),
      );
      salvarArquivo(pdf);
      const n = itens.length;
      this.sucesso.set(`✓ Orçamento gerado e guardado no histórico: ${n} ite${n > 1 ? 'ns' : 'm'}, ${itens.reduce((s, i) => s + i.opcoes.length, 0)} prints anexados.`);
      this.chamados().forEach(liberarAnexo);
      this.chamados.set([]);
      this.cesta.limpar();
      scrollTo({ top: 0, behavior: 'smooth' });
    } catch (e) {
      this.erros.set(await mensagensDeErro(e));
    } finally {
      this.gerando.set(false);
      this.avisos.terminar();
    }
  }
}
