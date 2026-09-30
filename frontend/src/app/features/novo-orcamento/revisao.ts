import { ChangeDetectionStrategy, Component, DestroyRef, computed, effect, inject, input, output, signal } from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { DatePipe, CurrencyPipe } from '@angular/common';
import { catchError, debounceTime, distinctUntilChanged, firstValueFrom, of, switchMap } from 'rxjs';
import { Api, salvarArquivo } from '../../core/api';
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
}>;

/**
 * Cards 2 e 3: revisão dos dados extraídos e geração do PDF.
 *
 * Fornecedor por linha (30/09/2026): a IA lê quem emitiu cada orçamento; se o sistema reconhece um fornecedor do
 * cadastro, o campo já vem com o nome padronizado ("cadastrado"); se não, com o nome lido ("novo — será cadastrado").
 */
@Component({
  selector: 'ha-revisao',
  imports: [ReactiveFormsModule, DatePipe, CurrencyPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <form [formGroup]="form" (ngSubmit)="enviar()">
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
          <div class="linha cols-2">
            <label>Número da loja
              <input formControlName="lojaNumero" placeholder="ex: 23" (input)="aoDigitarNumero()">
            </label>
            <label>Busca por nome
              <input formControlName="lojaBusca" placeholder="ex: DAMASIO PE" list="dl-lojas" (input)="aoBuscarNome()">
              <datalist id="dl-lojas">@for (l of lojas(); track l.numero) { <option [value]="l.nome"></option> }</datalist>
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
                <div class="item-fornecedor">
                  <input formControlName="fornecedor" placeholder="Fornecedor (quem emitiu o orçamento)" list="dl-fornecedores">
                  @if (situacaoFornecedor(i); as s) { <span class="selo-fornecedor" [class.novo]="s === 'novo'">{{ s === 'novo' ? 'novo — será cadastrado' : 'cadastrado' }}</span> }
                </div>
              </div>
            }
          </div>
          <datalist id="dl-fornecedores">@for (f of fornecedores(); track f.id) { <option [value]="f.nome"></option> }</datalist>
          <button type="button" class="btn-add" [disabled]="itens.length >= maxItens()" (click)="adicionarItem()">+ Adicionar item</button>

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
          <button class="btn-primario" type="submit" [disabled]="gerando()">↓ Baixar orçamento em PDF</button>
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
  `,
})
export class Revisao {
  protected readonly store = inject(OrcamentoStore);
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

  protected readonly form = this.fb.group({
    lojaNumero: '',
    lojaBusca: '',
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
      lojaNumero: ex.loja ? String(ex.loja.numero) : (d.loja_num ?? ''),
      lojaBusca: ex.loja?.nome ?? d.loja_nome ?? '',
    });
    this.loja.set(ex.loja);
  }

  private novoItem(i: Partial<ItemExtraido> = {}, f?: FornecedorSugerido): ItemForm {
    return this.fb.group({
      produto: i.produto ?? '',
      descricao: i.descricao ?? '',
      qtd: i.qtd ?? '1',
      unit: i.valor_unit ?? '',
      // o cadastrado ganha do lido: é o nome padronizado que o helpdesk quer ver no histórico
      fornecedor: f?.cadastrado?.nome ?? i.fornecedor ?? '',
      fornecedorCnpj: i.fornecedor_cnpj ?? '',
      fornecedorLido: i.fornecedor ?? '',
    });
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
    this.itens.removeAt(i);
  }

  /** "023" e "23" são a mesma loja. */
  protected aoDigitarNumero(): void {
    const n = parseInt(this.form.controls.lojaNumero.value.trim(), 10);
    const l = Number.isNaN(n) ? null : (this.lojas().find((x) => x.numero === n) ?? null);
    this.loja.set(l);
    if (l) this.form.controls.lojaBusca.setValue(l.nome);
  }

  protected aoBuscarNome(): void {
    const termo = this.form.controls.lojaBusca.value.trim().toLowerCase();
    if (termo.length < 2) return;
    const l = this.lojas().find((x) => x.nome.toLowerCase().includes(termo));
    if (l) {
      this.loja.set(l);
      this.form.controls.lojaNumero.setValue(String(l.numero));
    }
  }

  protected enviar(): void {
    const v = this.form.getRawValue();
    this.gerar.emit({
      modo: this.store.modo(),
      lojaNumero: this.loja() ? String(this.loja()!.numero) : v.lojaNumero,
      titulo: v.titulo,
      dataEmissao: v.dataEmissao,
      validade: v.validade || null,
      chamadoNum: this.store.exigeChamado() ? v.chamadoNum || null : null,
      itens: v.itens.map((i) => ({
        produto: i.produto,
        descricao: i.descricao,
        quantidade: parseFloat(i.qtd) || 1,
        valorUnitario: parseBRL(i.unit),
        fornecedor: i.fornecedor.trim() || undefined,
        fornecedorCnpj: i.fornecedorCnpj.trim() || undefined,
        fornecedorLido: i.fornecedorLido.trim() || undefined,
      })),
      subtotal: numeroOuNulo(v.subtotal),
      frete: numeroOuNulo(v.frete),
      acrescimos: numeroOuNulo(v.acrescimos),
      observacoes: v.observacoes,
      requerente: v.requerente,
      gestor: v.gestor,
    });
  }
}
