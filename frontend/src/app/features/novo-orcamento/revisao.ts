import { ChangeDetectionStrategy, Component, DestroyRef, computed, effect, inject, input, output, signal } from '@angular/core';
import { Avisos } from '../../core/avisos';
import { ImagensApi } from '../../core/imagem-api';
import { Recursos } from '../../core/recursos';
import { BuscaCotacao, EscolhaCotacao } from '../cotacao/busca-cotacao';
import { OpcaoCesta, alternativasPara, produtoDoTermo } from '../cotacao/cesta.store';
import { LinhasCotadas } from './linhas-cotadas.store';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { DatePipe, CurrencyPipe } from '@angular/common';
import { catchError, debounceTime, distinctUntilChanged, firstValueFrom, of, switchMap } from 'rxjs';
import { Api, mensagensDeErro, salvarArquivo } from '../../core/api';
import { FormArray, FormControl, FormGroup, NonNullableFormBuilder, ReactiveFormsModule } from '@angular/forms';
import { dataLocalISO, fmtBRL, numeroOuNulo, parseBRL } from '../../core/dinheiro';
import {
  Fornecedor,
  FornecedorSugerido,
  GerarOrcamentoRequest,
  ItemExtraido,
  ItemHistorico,
  Loja,
  Parametros,
  RespostaExtracao,
} from '../../core/modelos';
import { BuscaLoja } from '../../layout/busca-loja';
import { OrcamentoStore } from './orcamento.store';

type ItemForm = FormGroup<{
  produto: FormControl<string>;
  descricao: FormControl<string>;
  qtd: FormControl<string>;
  unit: FormControl<string>;
  /** Quem emitiu o orçamento desta linha (sugerido pela IA + cadastro; o atendente confirma). */
  fornecedor: FormControl<string>;
  fornecedorCnpj: FormControl<string>;
  /** Como a IA leu: vai junto para o servidor aprender o apelido. */
  fornecedorLido: FormControl<string>;
  /** Linha "adicionada por cotação": chave em {@link LinhasCotadas} (vazio = linha comum). */
  cotacao: FormControl<string>;
}>;

/**
 * Cards 2 e 3: revisão dos dados extraídos e geração do PDF.
 *
 * Fornecedor por linha (30/09/2026): a IA lê quem emitiu cada orçamento; se o sistema reconhece um fornecedor do
 * cadastro, o campo já vem com o nome padronizado ("cadastrado"); se não, com o nome lido ("novo — será cadastrado").
 *
 * Orçamento misto (30/09/2026): "Adicionar item" tem duas opções — manualmente, ou por cotação, que abre o painel
 * lateral com a busca da tela Cotação ({@link BuscaCotacao}). O anúncio escolhido vira uma linha do impresso (preço,
 * loja como fornecedor) e o servidor fotografa ele e mais 2 opções; o PDF continua único, com o resumo e os prints
 * das linhas cotadas antes do orçamento original do prestador. Só gera com todos os prints prontos.
 */
@Component({
  selector: 'ha-revisao',
  imports: [ReactiveFormsModule, DatePipe, CurrencyPipe, BuscaLoja, BuscaCotacao],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <!-- Ctrl+Enter em qualquer campo gera o PDF (atalho de 30/09/2026) -->
    <form [formGroup]="form" (ngSubmit)="enviar()"
      (keydown.control.enter)="atalhoGerar($event)" (keydown.meta.enter)="atalhoGerar($event)">
      <section class="card">
        <header class="card-header">
          <span class="card-num">2</span>
          <h2>Revisão dos dados</h2>
          <span class="card-sub">verifique antes de gerar</span>
        </header>
        <div class="card-body">
          @if (jaOrcados().length) {
            <div class="status alerta">
              <b>Este chamado já tem orçamento gerado:</b>
              <ul>
                @for (o of jaOrcados(); track o.id) {
                  <li>
                    {{ o.criadoEm | date: 'dd/MM/yyyy HH:mm' }} · {{ o.titulo }} · {{ o.total | currency: 'BRL' }}
                    · chamado {{ o.chamadoNum }} ·
                    <button type="button" class="btn-link" (click)="baixarAnterior(o)">baixar o PDF</button>
                  </li>
                }
              </ul>
              Confira se não é o mesmo pedido antes de gerar outro.
            </div>
          }
          @if (extracao()?.avisos?.length) {
            <div class="status erro">
              <b>Confira antes de gerar o PDF:</b>
              <ul>@for (a of extracao()!.avisos; track $index) { <li>{{ a }}</li> }</ul>
            </div>
          }

          <h3 class="rotulo-secao">Identificação da loja</h3>
          <ha-busca-loja [lojas]="lojas()" [(loja)]="loja" [sugestao]="sugestaoLoja()" />
          @if (loja(); as l) {
            <div class="loja-tag">
              <span class="loja-num">{{ l.numero }}</span>
              <span class="loja-nome">{{ l.nome }}</span>
              <span class="loja-cnpj">{{ l.cnpj }}</span>
              <span class="tmpl tmpl-{{ l.template }}">{{ l.templateRotulo }}</span>
            </div>
          }

          <hr>
          <h3 class="rotulo-secao">Dados do orçamento</h3>
          <label>Título do orçamento
            <input formControlName="titulo" placeholder="ex: Manutenção de Impressora TSC ME240">
          </label>
          <div class="linha cols-2">
            <label>Empresa <input [value]="loja()?.empresa ?? ''" readonly placeholder="preenchido automaticamente"></label>
            <label>CNPJ <input [value]="loja()?.cnpj ?? ''" readonly placeholder="preenchido automaticamente"></label>
          </div>
          <div class="linha cols-2">
            <label>Data de emissão <input type="date" formControlName="dataEmissao"></label>
            <label>Válido até
              <input type="date" formControlName="validade" [min]="form.controls.dataEmissao.value">
              <span class="ajuda">{{ origemValidade() }}</span>
            </label>
          </div>
          @if (store.exigeChamado()) {
            <label>Chamado(s) <input formControlName="chamadoNum" placeholder="ex: 1021069 ou 1021069, 1021070"></label>
          }

          <hr>
          <h3 class="rotulo-secao">Itens</h3>
          <div class="itens-head"><span>Ord</span><span>Produto</span><span>Descrição</span><span>Qtd</span><span>Unit</span><span>Total</span><span></span></div>
          <div formArrayName="itens">
            @for (g of itens.controls; track g; let i = $index) {
              <div class="item-row" [formGroupName]="i">
                <span class="item-ord">{{ (i + 1).toString().padStart(2, '0') }}</span>
                <input formControlName="produto" placeholder="Produto">
                <input formControlName="descricao" placeholder="Descrição técnica">
                <input formControlName="qtd" type="number" min="1" placeholder="1">
                <input formControlName="unit" placeholder="0,00">
                <input [value]="totaisLinha()[i] ? fmt(totaisLinha()[i]) : ''" readonly placeholder="0,00">
                <button type="button" class="btn-rm" (click)="removerItem(i)" title="Remover">×</button>
                @if (g.controls.cotacao.value; as chave) {
                  <!-- linha adicionada por cotação: fornecedor = loja do preço; os prints vão anexados ao PDF -->
                  <div class="item-fornecedor item-cotado">
                    <span class="selo-fornecedor cotado">cotado · {{ opcoesDe(chave)[0]?.fonte }}</span>
                    @for (o of opcoesDe(chave); track o.printId; let j = $index) {
                      <span class="print-chip" [class.escolhida]="j === 0" [class.falhou]="o.situacao === 'FALHOU'"
                        [class.alerta]="o.situacao === 'PRONTO' && !!o.alerta" [title]="o.motivo || o.alerta || o.titulo">
                        {{ j === 0 ? 'escolhida' : 'opção ' + (j + 1) }} · {{ o.fonte }}
                        @switch (o.situacao) {
                          @case ('CAPTURANDO') { <span class="oc-girando"></span> }
                          @case ('FALHOU') { <b>✕ não saiu</b> }
                          @default { <button type="button" class="btn-link" (click)="verPrint(o)">{{ o.alerta ? '⚠ conferir' : 'ver print' }}</button> }
                        }
                        @if (o.situacao === 'FALHOU' || (o.situacao === 'PRONTO' && o.alerta)) {
                          <button type="button" class="btn-link" (click)="recapturar(o)">tirar de novo</button>
                          <label class="btn-link oc-anexar">anexar print
                            <input type="file" accept="image/png,image/jpeg" (change)="anexarPrint(o, $event)" hidden>
                          </label>
                        }
                      </span>
                    }
                    @if (opcoesDe(chave)[0]?.url; as url) { <a class="link-loja" [href]="url" target="_blank" rel="noopener">ver na loja</a> }
                  </div>
                } @else {
                  <div class="item-fornecedor">
                    <input formControlName="fornecedor" placeholder="Fornecedor (quem emitiu o orçamento)" list="dl-fornecedores">
                    @if (situacaoFornecedor(i); as s) { <span class="selo-fornecedor" [class.novo]="s === 'novo'">{{ s === 'novo' ? 'novo — será cadastrado' : 'cadastrado' }}</span> }
                  </div>
                }
              </div>
            }
          </div>
          <datalist id="dl-fornecedores">@for (f of fornecedores(); track f.id) { <option [value]="f.nome"></option> }</datalist>
          <div class="add-itens">
            <button type="button" class="btn-add" [disabled]="cheio()" (click)="adicionarItem()">+ Adicionar manualmente</button>
            @if (recursos.cotacao()) {
              <button type="button" class="btn-add" [disabled]="cheio()" (click)="abrirCotacao()"
                title="Busca o item nas lojas online; o escolhido vira uma linha e os prints vão anexados ao PDF">🔍 Adicionar por cotação</button>
            }
            @if (cheio()) { <span class="ajuda">o impresso tem {{ maxItens() }} linhas</span> }
          </div>

          <div class="totais">
            <label>Subtotal <input formControlName="subtotal" placeholder="em branco"></label>
            <label>Frete <input formControlName="frete" placeholder="em branco"></label>
            <label>Acréscimos <input formControlName="acrescimos" placeholder="em branco"></label>
            <label class="destaque">Total geral <input [value]="totalGeral() ? fmt(totalGeral()) : ''" readonly placeholder="R$ 0,00"></label>
          </div>

          <hr>
          <h3 class="rotulo-secao">Responsáveis</h3>
          <div class="linha cols-2">
            <label>Requerente <input formControlName="requerente"></label>
            <label>Gestor <input formControlName="gestor"></label>
          </div>

          <hr>
          <h3 class="rotulo-secao">Observações</h3>
          <label>Observações (início: # nº chamado)
            <textarea formControlName="observacoes" rows="4" placeholder="# [número do chamado] — descrição técnica..."></textarea>
          </label>
        </div>
      </section>

      <section class="card">
        <header class="card-header">
          <span class="card-num">3</span>
          <h2>Gerar PDF</h2>
          <span class="card-sub">exportar orçamento preenchido</span>
        </header>
        <div class="card-body">
          @if (cotadas.capturando()) {
            <div class="status alerta"><b>Aguarde os prints da cotação:</b> {{ cotadas.capturando() }} ainda sendo tirado{{ cotadas.capturando() > 1 ? 's' : '' }} (≈15 s por item).</div>
          }
          @if (cotadas.alertas()) {
            <div class="status alerta"><b>{{ cotadas.alertas() }} print{{ cotadas.alertas() > 1 ? 's' : '' }} para conferir:</b> a conferência automática não achou o preço visível. Abra ("⚠ conferir"); se estiver certo, pode gerar.</div>
          }
          @if (cotadas.falhas()) {
            <div class="status erro"><b>{{ cotadas.falhas() }} print{{ cotadas.falhas() > 1 ? 's' : '' }} não saiu.</b> Tire de novo, anexe o seu ou remova a linha.</div>
          }
          <button class="btn-primario" type="submit" [disabled]="gerando() || !printsProntos()">↓ Baixar orçamento em PDF</button>
          <span class="ajuda atalho">ou <kbd>Ctrl</kbd> + <kbd>Enter</kbd> em qualquer campo</span>
          @if (erros().length) {
            <div class="status erro"><ul>@for (e of erros(); track $index) { <li>{{ e }}</li> }</ul></div>
          }
          @if (sucesso(); as s) { <div class="status ok">{{ s }}</div> }
          @if (store.pdfGerado()) {
            <button class="btn-secundario" type="button" (click)="novo.emit()">↺ Novo orçamento (voltar ao início)</button>
          }
        </div>
      </section>
    </form>

    <!-- Painel "Adicionar por cotação": criado na primeira vez e mantido (a última busca continua lá ao reabrir). -->
    @if (gavetaUsada()) {
      <div class="gaveta-fundo" [class.aberta]="gaveta()" (click)="fecharCotacao()"></div>
      <aside class="gaveta-cotacao" [class.aberta]="gaveta()" role="dialog" aria-label="Adicionar item por cotação"
        [attr.aria-hidden]="!gaveta()" (keydown.escape)="fecharCotacao()">
        <header class="gaveta-cab">
          <div>
            <div class="sobretitulo">Novo orçamento · item {{ qtdItens() + 1 }} de até {{ maxItens() }}</div>
            <h2>Adicionar por cotação</h2>
            <p>Busque o item que o prestador não incluiu. O escolhido vira uma linha do impresso e os prints (dele e de mais
              2 opções) vão anexados ao PDF, antes do orçamento original.</p>
          </div>
          <button type="button" class="btn-rm" (click)="fecharCotacao()" title="Fechar (Esc)">×</button>
        </header>
        <div class="gaveta-corpo">
          <ha-busca-cotacao [sugestoes]="false" [escolhidas]="cotadas.urls()" [cheia]="cheio()" (escolher)="escolherCotacao($event)" />
        </div>
      </aside>
    }
  `,
})
export class Revisao {
  protected readonly store = inject(OrcamentoStore);
  protected readonly cotadas = inject(LinhasCotadas);
  protected readonly recursos = inject(Recursos);
  private readonly avisos = inject(Avisos);
  private readonly imagens = inject(ImagensApi);
  private readonly fb = inject(NonNullableFormBuilder);

  readonly lojas = input.required<Loja[]>();
  readonly parametros = input.required<Parametros>();
  readonly extracao = input<RespostaExtracao | null>(null);
  readonly gerando = input(false);
  readonly erros = input<string[]>([]);
  readonly sucesso = input<string | null>(null);

  readonly gerar = output<GerarOrcamentoRequest>();
  readonly novo = output<void>();

  protected readonly loja = signal<Loja | null>(null);
  /** O que a IA leu da loja quando não bateu com o cadastro — aparece no campo de busca para o atendente escolher. */
  protected readonly sugestaoLoja = signal('');
  private readonly api = inject(Api);
  /** Orçamentos já gerados para o(s) chamado(s) do formulário. */
  protected readonly jaOrcados = signal<ItemHistorico[]>([]);
  /** De onde veio a validade pré-preenchida — para o atendente saber se foi lida ou precisa preencher. */
  protected readonly origemValidade = signal('Opcional — o documento não informa validade.');
  protected readonly maxItens = computed(() => this.parametros().maxItens);
  /** Fornecedores ativos: sugestão no campo e para dizer se o nome digitado já é cadastrado. */
  protected readonly fornecedores = toSignal(this.api.fornecedores().pipe(catchError(() => of([] as Fornecedor[]))), {
    initialValue: [] as Fornecedor[],
  });
  protected readonly fmt = fmtBRL;
  /** Painel "Adicionar por cotação" aberto; {@link gavetaUsada} = já foi aberto uma vez (mantém a busca). */
  protected readonly gaveta = signal(false);
  protected readonly gavetaUsada = signal(false);

  protected readonly form = this.fb.group({
    titulo: '',
    dataEmissao: dataLocalISO(),
    validade: '',
    chamadoNum: '',
    itens: this.fb.array<ItemForm>([]),
    subtotal: '',
    frete: '',
    acrescimos: '',
    requerente: '',
    gestor: '',
    observacoes: '',
  });

  protected get itens(): FormArray<ItemForm> {
    return this.form.controls.itens;
  }

  private readonly valor = toSignal(this.form.valueChanges, { initialValue: this.form.getRawValue() });

  protected readonly totaisLinha = computed(() =>
    (this.valor().itens ?? []).map((i) => (parseFloat(i?.qtd ?? '') || 0) * parseBRL(i?.unit ?? '')),
  );

  protected readonly qtdItens = computed(() => this.valor().itens?.length ?? 0);
  protected readonly cheio = computed(() => this.qtdItens() >= this.maxItens());
  /** Sem print sendo tirado nem com falha: o servidor recusaria o PDF sem eles. */
  protected readonly printsProntos = computed(() => !this.cotadas.capturando() && !this.cotadas.falhas());

  protected readonly totalGeral = computed(
    () => this.totaisLinha().reduce((a, b) => a + b, 0) + parseBRL(this.valor().frete) + parseBRL(this.valor().acrescimos),
  );

  constructor() {
    // Reconfere a duplicidade quando o atendente corrige o número do chamado (a leitura da IA pode errar um dígito).
    this.form.controls.chamadoNum.valueChanges
      .pipe(
        debounceTime(500),
        distinctUntilChanged(),
        switchMap((n) => (/\d{4,}/.test(n) ? this.api.porChamado(n).pipe(catchError(() => of([]))) : of([]))),
        takeUntilDestroyed(inject(DestroyRef)),
      )
      .subscribe((lista) => this.jaOrcados.set(lista));

    // Preenche o formulário quando chega (ou muda) a extração da IA.
    effect(() => {
      const ex = this.extracao();
      const p = this.parametros();
      this.form.patchValue({ requerente: p.requerentePadrao, gestor: p.gestorPadrao }, { emitEvent: false });
      if (ex) this.preencher(ex);
      else if (this.itens.length === 0) this.adicionarItem();
    });
  }

  private preencher(ex: RespostaExtracao): void {
    const d = ex.dados;
    this.itens.clear({ emitEvent: false });
    this.cotadas.limpar(); // leitura nova recomeça a lista de itens, cotados inclusive
    const itens = d.itens.slice(0, this.maxItens());
    (itens.length ? itens : [{} as ItemExtraido]).forEach((i, n) =>
      this.itens.push(this.novoItem(i, ex.fornecedores?.[n]), { emitEvent: false }),
    );
    this.jaOrcados.set(ex.chamadosJaOrcados ?? []);
    const validadeEscrita = !!d.validade_ate?.trim();
    this.origemValidade.set(
      ex.validadeSugerida
        ? validadeEscrita
          ? 'Lida do orçamento (data informada no documento).'
          : `Calculada: ${d.validade_dias} dia(s) de validade informados no documento.`
        : 'Opcional — o documento não informa validade.',
    );
    this.form.patchValue({
      validade: ex.validadeSugerida ?? '',
      titulo: d.titulo ?? '',
      chamadoNum: d.chamado_num ?? '',
      observacoes: d.observacao ?? '',
    });
    this.loja.set(ex.loja);
    this.sugestaoLoja.set(ex.loja ? '' : [d.loja_num, d.loja_nome].filter((x) => x?.trim()).join(' ').trim());
  }

  private novoItem(i: Partial<ItemExtraido> = {}, f?: FornecedorSugerido, cotacao = ''): ItemForm {
    return this.fb.group({
      produto: i.produto ?? '',
      descricao: i.descricao ?? '',
      qtd: i.qtd ?? '1',
      unit: i.valor_unit ?? '',
      // o cadastrado ganha do lido: é o nome padronizado que o helpdesk quer ver no histórico
      fornecedor: f?.cadastrado?.nome ?? i.fornecedor ?? '',
      fornecedorCnpj: i.fornecedor_cnpj ?? '',
      fornecedorLido: i.fornecedor ?? '',
      cotacao,
    });
  }

  protected opcoesDe(chave: string): OpcaoCesta[] {
    return this.cotadas.opcoes()[chave] ?? [];
  }

  protected abrirCotacao(): void {
    this.gavetaUsada.set(true);
    this.gaveta.set(true);
  }

  protected fecharCotacao(): void {
    this.gaveta.set(false);
  }

  /** O anúncio escolhido no painel vira uma linha (no lugar da linha vazia, se só houver ela) e os prints começam. */
  protected async escolherCotacao({ resposta: r, avaliado: a }: EscolhaCotacao): Promise<void> {
    if (this.itens.length >= this.maxItens()) {
      this.avisos.toast(`O impresso tem ${this.maxItens()} linhas: remova uma para adicionar outra.`, '⚠');
      return;
    }
    try {
      const alternativas = alternativasPara(r.resultado, a);
      const l = await this.cotadas.adicionar(r.id, a, alternativas);
      const primeira = this.itens.length === 1 ? this.itens.at(0).getRawValue() : null;
      if (primeira && !primeira.produto.trim() && !primeira.cotacao) this.itens.removeAt(0);
      this.itens.push(this.novoItem({ produto: produtoDoTermo(r.termo), descricao: `${l.fonte} · ${l.titulo}`.slice(0, 500),
        qtd: '1', valor_unit: fmtBRL(l.preco), fornecedor: l.fonte }, undefined, l.chave));
      this.fecharCotacao();
      this.avisos.toast(`Item ${this.itens.length} adicionado por cotação · tirando ${alternativas.length + 1} prints`);
    } catch (e) {
      this.avisos.toast(e instanceof Error ? e.message : (await mensagensDeErro(e)).join(' '), '⚠');
    }
  }

  protected verPrint(o: OpcaoCesta): void {
    this.imagens.abrir(this.cotadas.urlImagem(o));
  }

  protected async recapturar(o: OpcaoCesta): Promise<void> {
    try {
      await this.cotadas.recapturar(o.printId);
    } catch (e) {
      this.avisos.toast((await mensagensDeErro(e)).join(' '), '⚠');
    }
  }

  protected async anexarPrint(o: OpcaoCesta, ev: Event): Promise<void> {
    const input = ev.target as HTMLInputElement;
    const arq = input.files?.[0];
    input.value = '';
    if (!arq) return;
    try {
      await this.cotadas.anexarManual(o.printId, arq, arq.name);
      this.avisos.toast(`Print da ${o.fonte} substituído`);
    } catch (e) {
      this.avisos.toast((await mensagensDeErro(e)).join(' '), '⚠');
    }
  }

  /** 'cadastrado' se o nome bate com um fornecedor do cadastro (sem diferenciar maiúsculas); 'novo' se não; null vazio. */
  protected situacaoFornecedor(i: number): 'cadastrado' | 'novo' | null {
    const nome = (this.valor().itens?.[i]?.fornecedor ?? '').trim().toLowerCase();
    if (!nome) return null;
    return this.fornecedores().some((f) => f.nome.toLowerCase() === nome) ? 'cadastrado' : 'novo';
  }

  protected async baixarAnterior(o: ItemHistorico): Promise<void> {
    salvarArquivo(await firstValueFrom(this.api.baixar(o.id)));
  }

  protected adicionarItem(): void {
    if (this.itens.length < this.maxItens()) this.itens.push(this.novoItem());
  }

  protected removerItem(i: number): void {
    const chave = this.itens.at(i).controls.cotacao.value;
    if (chave) this.cotadas.remover(chave);
    this.itens.removeAt(i);
  }

  protected atalhoGerar(ev: Event): void {
    ev.preventDefault();
    if (!this.gerando() && this.printsProntos()) this.enviar();
  }

  protected enviar(): void {
    const v = this.form.getRawValue();
    this.gerar.emit({
      modo: this.store.modo(),
      // sem loja escolhida o servidor responde "Selecione a loja"
      lojaNumero: this.loja() ? String(this.loja()!.numero) : '',
      titulo: v.titulo,
      dataEmissao: v.dataEmissao,
      validade: v.validade || null,
      chamadoNum: this.store.exigeChamado() ? v.chamadoNum || null : null,
      itens: v.itens.map((i) => ({
        produto: i.produto,
        descricao: i.descricao,
        quantidade: parseFloat(i.qtd) || 1,
        valorUnitario: parseBRL(i.unit),
        // linha cotada: o servidor anexa os prints e usa a loja do preço como fornecedor
        ...(i.cotacao
          ? { prints: this.cotadas.prints(i.cotacao) }
          : {
              fornecedor: i.fornecedor.trim() || undefined,
              fornecedorCnpj: i.fornecedorCnpj.trim() || undefined,
              fornecedorLido: i.fornecedorLido.trim() || undefined,
            }),
      })),
      subtotal: numeroOuNulo(v.subtotal),
      frete: numeroOuNulo(v.frete),
      acrescimos: numeroOuNulo(v.acrescimos),
      observacoes: v.observacoes,
      requerente: v.requerente,
      gestor: v.gestor,
      // o servidor compara o que a IA leu com o que foi confirmado aqui (aprender com as correções)
      idLeitura: this.extracao()?.idLeitura ?? null,
    });
  }
}
