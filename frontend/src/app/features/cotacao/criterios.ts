import { ChangeDetectionStrategy, Component, computed, effect, inject, input, output, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule } from '@angular/forms';
import { CriteriosCotacao, Origem } from './cotacao.api';

type ChavePeso = 'pesoPreco' | 'pesoEntrega' | 'pesoFornecedor' | 'pesoMarca';

/** Os quatro pesos, na ordem da barra de score. A cor de cada um vem de tokens em styles.scss (--cot-*). */
export const PESOS: { chave: ChavePeso; nome: string; classe: string; parcial: 'preco' | 'entrega' | 'fornecedor' | 'marca' }[] = [
  { chave: 'pesoPreco', nome: 'Preço', classe: 'c-preco', parcial: 'preco' },
  { chave: 'pesoEntrega', nome: 'Entrega rápida', classe: 'c-entrega', parcial: 'entrega' },
  { chave: 'pesoFornecedor', nome: 'Fornecedor confiável', classe: 'c-fornecedor', parcial: 'fornecedor' },
  { chave: 'pesoMarca', nome: 'Marca', classe: 'c-marca', parcial: 'marca' },
];

/**
 * Painel "Critérios de decisão": pesos do score, eliminatórios e segmentação. Emite o critério completo a cada
 * mudança; a página decide se reavalia (com resultado na tela) ou só guarda para a próxima busca.
 *
 * Os pesos não precisam somar 100 — o servidor normaliza; a tela mostra o percentual efetivo de cada um.
 * O "padrão" vem do servidor (política de Suprimentos, CriteriosCotacao.PADRAO). Os valores iniciais do
 * formulário abaixo só valem até /api/cotacao/estado responder — mudou a política, muda no Java.
 */
@Component({
  selector: 'ha-criterios-cotacao',
  imports: [ReactiveFormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <details class="cot-criterios" [open]="aberto()" (toggle)="aberto.set($any($event.target).open)">
      <summary>
        <span class="cot-criterios-titulo">Critérios de decisão</span>
        <span class="cot-resumo-pesos">{{ resumoPesos() }}</span>
        <span class="cot-seta" aria-hidden="true"></span>
      </summary>

      <form [formGroup]="form" class="cot-criterios-corpo">
        <section class="cot-bloco">
          <h3>Pesos do score</h3>
          <p>O que mais importa nesta compra. A soma é normalizada: o que vale é a proporção.</p>
          <div class="cot-pesos">
            @for (p of pesos; track p.chave) {
              <label>
                <span class="cot-peso-topo"><span><i [class]="'cot-bola ' + p.classe"></i>{{ p.nome }}</span><output>{{ percentual()[p.chave] }}%</output></span>
                <input type="range" min="0" max="100" step="5" [formControlName]="p.chave">
              </label>
            }
          </div>
          <div class="cot-barra-pesos" aria-hidden="true">
            @for (p of pesos; track p.chave) { <i [class]="p.classe" [style.width.%]="percentual()[p.chave]"></i> }
          </div>
        </section>

        <section class="cot-bloco">
          <h3>Eliminatórios</h3>
          <p>Aplicados <b>antes</b> do score. Quem não passa aqui não compete por preço.</p>
          <div class="cot-grade">
            <label>Nota mínima do vendedor
              <input type="number" min="0" max="5" step="0.1" formControlName="notaMinima">
            </label>
            <label>Mínimo de unidades vendidas
              <input type="number" min="0" step="10" formControlName="vendidosMinimo">
              <span class="ajuda">só vale quando o anúncio informa a quantidade</span>
            </label>
            <label>Origem do envio
              <select formControlName="origem">
                <option value="qualquer">Qualquer origem</option>
                <option value="nacional">Só nacional</option>
                <option value="internacional">Só internacional</option>
              </select>
              <span class="ajuda">internacional costuma ser mais barato e demorar semanas</span>
            </label>
            <label>Teto de preço (R$)
              <input type="number" min="0" step="10" formControlName="precoMax" placeholder="sem teto">
            </label>
            <label>Título deve conter
              <input formControlName="deveConter" placeholder="ex: 256, abnt2">
              <span class="ajuda">separe por vírgula · garante que é o item certo</span>
            </label>
            <label>Título não pode conter
              <input formControlName="naoPodeConter" placeholder="ex: kit, usado, adaptador">
              <span class="ajuda">corta combos e acessórios parecidos</span>
            </label>
            <div class="cot-switches">
              <label class="switch" title="FULL no Mercado Livre, Prime na Amazon"><input type="checkbox" formControlName="exigirFull"><span class="switch-ui"></span><span>Só com entrega FULL / Prime</span></label>
              <label class="switch"><input type="checkbox" formControlName="aceitaRecondicionado"><span class="switch-ui"></span><span>Aceitar recondicionado</span></label>
              <label class="switch" title="Pichau, Dell e boa parte da Kabum não mostram nota na busca. Ligado: a reputação é a da loja (nota presumida 4,5). Desligado: descarta, como o piloto fazia.">
                <input type="checkbox" formControlName="aceitarLojaPropriaSemNota"><span class="switch-ui"></span><span>Aceitar sem nota quando a própria loja vende</span>
              </label>
            </div>
          </div>
        </section>

        <section class="cot-bloco">
          <h3>Refinos</h3>
          <p>Quando aplicável. Em branco, vale o padrão.</p>
          <div class="cot-grade">
            <label>Marcas preferidas
              <input formControlName="marcasPreferidas" placeholder="ex: samsung, kingston, wd">
              <span class="ajuda">em branco: lista padrão de marcas de TI</span>
            </label>
            <label>Segmentar o ranking por
              <input formControlName="segmentos" placeholder="ex: M.2, SATA">
              <span class="ajuda">um top 3 por segmento — evita comparar peças incompatíveis</span>
            </label>
          </div>
          <button type="button" class="btn-link" (click)="restaurar()">Restaurar o padrão de Suprimentos</button>
        </section>
      </form>
    </details>
  `,
})
export class CriteriosCotacaoPainel {
  /** Padrão do servidor; ao chegar, preenche o formulário. */
  readonly padrao = input<CriteriosCotacao | null>(null);
  readonly alterado = output<CriteriosCotacao>();

  protected readonly pesos = PESOS;
  protected readonly aberto = signal(false);
  private readonly fb = inject(NonNullableFormBuilder);

  protected readonly form = this.fb.group({
    pesoPreco: 40,
    pesoEntrega: 20,
    pesoFornecedor: 25,
    pesoMarca: 15,
    notaMinima: 4.5,
    vendidosMinimo: 100,
    aceitaRecondicionado: false,
    exigirFull: false,
    origem: 'qualquer' as Origem,
    precoMax: null as number | null,
    deveConter: '',
    naoPodeConter: '',
    marcasPreferidas: '',
    segmentos: '',
    aceitarLojaPropriaSemNota: true,
  });

  /** Os campos de pontuação de entrega não aparecem na tela: ficam com o padrão do servidor. */
  private extras = { pontosFull: 100, pontosFreteGratis: 60, pontosSemFrete: 20 };
  private readonly valores = signal(this.form.getRawValue());

  protected readonly percentual = computed(() => {
    const v = this.valores();
    const soma = PESOS.reduce((s, p) => s + Number(v[p.chave]), 0) || 1;
    return Object.fromEntries(PESOS.map((p) => [p.chave, Math.round((100 * Number(v[p.chave])) / soma)])) as Record<ChavePeso, number>;
  });

  protected readonly resumoPesos = computed(() =>
    PESOS.map((p) => `${p.nome.split(' ')[0].toLowerCase()} ${this.percentual()[p.chave]}`).join(' · '),
  );

  constructor() {
    effect(() => {
      const p = this.padrao();
      if (p) this.aplicar(p, false);
    });
    this.form.valueChanges.subscribe(() => {
      this.valores.set(this.form.getRawValue());
      this.alterado.emit(this.valor());
    });
  }

  /** Critério completo, no formato da API. */
  valor(): CriteriosCotacao {
    const v = this.form.getRawValue();
    return {
      ...v,
      ...this.extras,
      precoMax: v.precoMax ? Number(v.precoMax) : null,
      notaMinima: Number(v.notaMinima),
      vendidosMinimo: Number(v.vendidosMinimo),
      pesoPreco: Number(v.pesoPreco),
      pesoEntrega: Number(v.pesoEntrega),
      pesoFornecedor: Number(v.pesoFornecedor),
      pesoMarca: Number(v.pesoMarca),
    };
  }

  /**
   * Sugestão rápida da tela ("Testar com"): ajusta só os campos informados, sem avisar a página —
   * quem chama já vai disparar uma cotação nova com estes critérios.
   */
  sugerir(parcial: Partial<CriteriosCotacao>): void {
    this.form.patchValue(parcial, { emitEvent: false });
    this.valores.set(this.form.getRawValue());
  }

  protected restaurar(): void {
    const p = this.padrao();
    if (p) this.aplicar(p, true);
  }

  private aplicar(p: CriteriosCotacao, avisar: boolean): void {
    const { pontosFull, pontosFreteGratis, pontosSemFrete, ...campos } = p;
    this.extras = { pontosFull, pontosFreteGratis, pontosSemFrete };
    this.form.setValue(campos, { emitEvent: avisar });
    this.valores.set(this.form.getRawValue());
  }
}
