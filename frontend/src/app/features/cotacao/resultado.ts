import { CurrencyPipe, DatePipe, DecimalPipe, NgTemplateOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { Icone } from '../../layout/icone';
import { PESOS } from './criterios';
import { Anuncio, Avaliado, RespostaCotacao } from './cotacao.api';

/**
 * Resultado da cotação: números do resumo, lojas consultadas, avisos, o melhor de cada loja, top 3 de cada grupo
 * (segmento) em cartões, a tabela dos demais e os descartados com o motivo. Só exibe — toda regra (eliminatórios,
 * score) é do servidor. Cada anúncio elegível tem "escolher para o orçamento" (orçamento por cotação).
 */
@Component({
  selector: 'ha-resultado-cotacao',
  imports: [CurrencyPipe, DatePipe, DecimalPipe, NgTemplateOutlet, Icone],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @let r = resposta().resultado;
    <section class="cot-resultado" [class.cot-atualizando]="atualizando()">
      <div class="cot-resultado-cab">
        <div>
          <div class="sobretitulo">Resultado</div>
          <h2>{{ resposta().termo }}</h2>
          <p>Coletado em {{ resposta().coletadoEm | date: "dd/MM/yyyy 'às' HH:mm" }} · {{ lojasConsultadas().length }} loja{{ lojasConsultadas().length > 1 ? 's' : '' }} · {{ resposta().segundos | number: '1.1-1' }} s</p>
        </div>
        <button class="btn-acao" (click)="baixarPlanilha.emit()" title="Resumo, critérios, análise e descartados, com links">
          <ha-icone nome="baixar" [tamanho]="15" /> Baixar planilha
        </button>
      </div>

      <div class="cot-kpis">
        <div><b>{{ r.resumo?.analisados ?? r.descartados.length }}</b><span>anúncios analisados</span></div>
        <div><b>{{ r.elegiveis.length }}</b><span>passaram nos eliminatórios</span></div>
        <div><b class="cot-kpi-desc">{{ r.descartados.length }}</b><span>descartados</span></div>
        @if (r.resumo; as s) {
          <div><b>{{ s.menorPreco | currency: 'BRL' }}</b><span>menor preço elegível</span></div>
          <div><b>{{ s.precoMedio | currency: 'BRL' }}</b><span>preço médio</span></div>
          <div><b>{{ s.mediana | currency: 'BRL' }}</b><span>mediana</span></div>
        }
      </div>

      <div class="cot-lojas-resultado" aria-label="Anúncios por loja">
        @for (l of lojasConsultadas(); track l.nome) {
          <span [class.vazia]="!l.qtd" [title]="l.qtd ? l.qtd + ' anúncios aproveitados' : 'nenhum anúncio para o termo'">
            {{ l.nome }} <b>{{ l.qtd }}</b>
          </span>
        }
      </div>

      <div class="cot-avisos">
        <div class="status erro">
          <b>Preço de referência.</b> Coletado agora; as lojas mudam o preço conforme a conta logada, o CEP e as
          promoções do dia. Confirme na loja antes de fechar a compra.
        </div>
        @for (a of avisos(); track $index) {
          <div class="status alerta"><b>{{ a.titulo }}</b> {{ a.texto }}</div>
        }
        @if (!r.elegiveis.length) {
          <div class="status erro"><b>Nenhum anúncio passou nos eliminatórios.</b> Afrouxe os critérios — os motivos estão abaixo.</div>
        }
      </div>

      @if (melhorPorLoja().length > 1) {
        <section class="cot-grupo">
          <div class="cot-grupo-cab">
            <h3>Melhor de cada loja</h3>
            <span>o anúncio de maior score em cada loja — a comparação lado a lado</span>
          </div>
          <div class="cot-tabela">
            <div class="cot-linha melhor cabecalho"><span>Loja</span><span>Produto</span><span>Preço</span><span>Score</span><span>Anúncio</span></div>
            @for (a of melhorPorLoja(); track a.anuncio.fonte) {
              <div class="cot-linha melhor" [class.cot-destaque]="a === r.elegiveis[0]">
                <span class="cot-loja-nome">{{ a.anuncio.fonte }}</span>
                <span class="cot-prod" [title]="a.anuncio.titulo">{{ a.anuncio.titulo }}</span>
                <span class="mono">{{ a.anuncio.preco | currency: 'BRL' }}</span>
                <span class="mono">{{ a.score | number: '1.1-1' }}</span>
                <span class="cot-acoes-linha">
                  @if (a.anuncio.url) { <a [href]="a.anuncio.url" target="_blank" rel="noopener">abrir</a> }
                  <ng-container *ngTemplateOutlet="botaoEscolher; context: { $implicit: a, curto: true }" />
                </span>
              </div>
            }
          </div>
        </section>
      }

      @for (g of r.grupos; track g.rotulo) {
        <section class="cot-grupo">
          <div class="cot-grupo-cab">
            <h3>{{ g.rotulo }}</h3>
            <span>{{ g.qtd }} elegíveis · menor {{ g.menorPreco | currency: 'BRL' }} · médio {{ g.precoMedio | currency: 'BRL' }}</span>
          </div>
          <div class="cot-podio">
            @for (a of g.top3; track a.anuncio.url + a.anuncio.titulo; let i = $index) {
              <article class="cot-cartao" [class.cot-primeiro]="i === 0">
                <span class="cot-pos">{{ ['① melhor escolha', '② vice', '③ terceiro'][i] }}</span>
                <span class="cot-loja-cartao">{{ a.anuncio.fonte }}</span>
                <h4>{{ a.anuncio.titulo }}</h4>
                <div class="cot-preco">
                  {{ a.anuncio.preco | currency: 'BRL' }}
                  @if (a.anuncio.precoDe && a.anuncio.precoDe > a.anuncio.preco) { <s>{{ a.anuncio.precoDe | currency: 'BRL' }}</s> }
                </div>
                <div class="cot-selos">
                  @for (s of selos(a.anuncio); track s.texto) { <span [class]="'cot-selo ' + s.classe">{{ s.texto }}</span> }
                </div>
                <div class="cot-score">
                  <div class="cot-score-topo"><span>Score</span><b>{{ a.score | number: '1.1-1' }}</b></div>
                  <div class="cot-score-barra">
                    @for (p of pesos; track p.parcial) { <i [class]="p.classe" [style.width.%]="contribuicao(a, p.parcial)" [title]="p.nome + ': ' + (contribuicao(a, p.parcial) | number: '1.1-1') + ' pts'"></i> }
                  </div>
                  <div class="cot-score-leg">
                    @for (p of pesos; track p.parcial) { <span><i [class]="'cot-bola ' + p.classe"></i>{{ p.nome.split(' ')[0] }} {{ contribuicao(a, p.parcial) | number: '1.0-0' }}</span> }
                  </div>
                </div>
                <div class="cot-rodape">
                  <span>{{ vendedor(a.anuncio) }}</span>
                  @if (a.anuncio.url) {
                    <a [href]="a.anuncio.url" target="_blank" rel="noopener" [title]="a.anuncio.patrocinado ? 'anúncio patrocinado' : ''">Ver na loja{{ a.anuncio.patrocinado ? ' (ad)' : '' }}</a>
                  } @else { <span class="cot-sem-link">sem link</span> }
                </div>
                <ng-container *ngTemplateOutlet="botaoEscolher; context: { $implicit: a, curto: false }" />
              </article>
            }
          </div>

          @if (g.demais.length) {
            <div class="cot-tabela">
              <div class="cot-linha cabecalho"><span>#</span><span>Produto</span><span>Loja</span><span>Preço</span><span>Score</span><span>Nota</span><span>Vendidos</span><span>Entrega</span><span>Anúncio</span></div>
              @for (a of g.demais; track a.anuncio.url + a.anuncio.titulo; let i = $index) {
                <div class="cot-linha">
                  <span class="mono">{{ i + 4 }}</span>
                  <span class="cot-prod" [title]="a.anuncio.titulo">{{ a.anuncio.titulo }}</span>
                  <span class="cot-loja-nome">{{ a.anuncio.fonte }}</span>
                  <span class="mono">{{ a.anuncio.preco | currency: 'BRL' }}</span>
                  <span class="mono">{{ a.score | number: '1.1-1' }}</span>
                  <span class="mono">{{ a.anuncio.nota === null ? '—' : (a.anuncio.nota | number: '1.1-1') }}</span>
                  <span class="mono">{{ a.anuncio.vendidos === null ? '—' : (a.anuncio.vendidos | number) }}</span>
                  <span [class.cot-int]="a.anuncio.internacional">{{ entrega(a.anuncio) }}{{ a.anuncio.internacional ? ' · ' + origem(a.anuncio) : '' }}</span>
                  <span class="cot-acoes-linha">
                    @if (a.anuncio.url) { <a [href]="a.anuncio.url" target="_blank" rel="noopener">abrir</a>@if (a.anuncio.patrocinado) {<span class="cot-ad">ad</span>} }
                    @else { <span class="cot-sem-link">sem link</span> }
                    <ng-container *ngTemplateOutlet="botaoEscolher; context: { $implicit: a, curto: true }" />
                  </span>
                </div>
              }
            </div>
          }
        </section>
      }

      @if (r.descartados.length) {
        <details class="cot-descartados">
          <summary><span>{{ r.descartados.length }} anúncios descartados e por quê</span><span class="cot-seta" aria-hidden="true"></span></summary>
          <div class="cot-tabela">
            <div class="cot-linha desc cabecalho"><span>Produto</span><span>Loja</span><span>Preço</span><span>Motivo do descarte</span><span>Anúncio</span></div>
            @for (d of descartadosOrdenados(); track d.anuncio.url + d.anuncio.titulo) {
              <div class="cot-linha desc">
                <span class="cot-prod" [title]="d.anuncio.titulo">{{ d.anuncio.titulo }}</span>
                <span class="cot-loja-nome">{{ d.anuncio.fonte }}</span>
                <span class="mono">{{ d.anuncio.preco | currency: 'BRL' }}</span>
                <span class="cot-motivo">{{ d.motivo }}</span>
                <span>
                  @if (d.anuncio.url) { <a [href]="d.anuncio.url" target="_blank" rel="noopener">abrir</a>@if (d.anuncio.patrocinado) {<span class="cot-ad">ad</span>} }
                  @else { <span class="cot-sem-link">sem link</span> }
                </span>
              </div>
            }
          </div>
        </details>
      }
    </section>

    <!-- Orçamento por cotação: a escolhida vira a linha do impresso; o sistema fotografa ela e mais 2 opções. -->
    <ng-template #botaoEscolher let-a let-curto="curto">
      @if (a.anuncio.url) {
        @if (escolhidas().has(a.anuncio.url)) {
          <span class="cot-na-cesta" title="Já está no orçamento por cotação">✓ {{ curto ? 'na cesta' : 'No orçamento' }}</span>
        } @else {
          <button type="button" [class]="curto ? 'cot-escolher curto' : 'cot-escolher'" [disabled]="cestaCheia()" (click)="escolher.emit(a)"
            [title]="cestaCheia() ? 'O impresso tem 10 linhas: a cesta está cheia' : 'Põe no orçamento por cotação e tira os prints desta e de mais 2 opções'">
            + {{ curto ? 'orçamento' : 'Escolher para o orçamento' }}
          </button>
        }
      }
    </ng-template>
  `,
})
export class ResultadoCotacaoView {
  readonly resposta = input.required<RespostaCotacao>();
  /** Reavaliando com critérios novos: a tela esmaece em vez de sumir. */
  readonly atualizando = input(false);
  readonly baixarPlanilha = output<void>();
  /** URLs que já estão na cesta do orçamento por cotação. */
  readonly escolhidas = input<Set<string>>(new Set());
  readonly cestaCheia = input(false);
  /** "Escolher para o orçamento": a página calcula as alternativas e põe na cesta. */
  readonly escolher = output<Avaliado>();

  protected readonly pesos = PESOS;

  /** Lojas consultadas com quantos anúncios cada uma rendeu, na ordem pedida. */
  protected readonly lojasConsultadas = computed(() =>
    Object.entries(this.resposta().porFonte ?? {}).map(([nome, qtd]) => ({ nome, qtd })),
  );

  /** O elegível de maior score de cada loja (a lista já vem ordenada por score), para comparar lado a lado. */
  protected readonly melhorPorLoja = computed(() => {
    const vistos = new Set<string>();
    return this.resposta().resultado.elegiveis.filter((a) => !vistos.has(a.anuncio.fonte) && vistos.add(a.anuncio.fonte));
  });

  protected readonly descartadosOrdenados = computed(() =>
    [...this.resposta().resultado.descartados].sort((a, b) => a.motivo.localeCompare(b.motivo, 'pt-BR')),
  );

  /** Mesmos avisos do piloto: explicam por que o ranking pode enganar. */
  protected readonly avisos = computed(() => {
    const { resultado: r, avisos: coleta, esperouFila } = this.resposta();
    const lista: { titulo: string; texto: string }[] = [];
    const fora = r.grupos.find((g) => g.semSegmento);
    if (fora?.qtd) {
      lista.push({ titulo: `${fora.qtd} anúncios ficaram fora dos segmentos.`,
        texto: 'O título deles não contém nenhum segmento pedido; têm ranking próprio abaixo — confira o item antes de comparar.' });
    }
    if (r.resumo?.internacionais && r.criterios.origem === 'qualquer') {
      lista.push({ titulo: `${r.resumo.internacionais} dos ${r.resumo.elegiveis} elegíveis vêm do exterior.`,
        texto: 'Costuma ser mais barato, mas o prazo é de semanas e pode haver tributação. Se o prazo importa, use "só nacional".' });
    }
    if (r.resumo?.semVolume) {
      lista.push({ titulo: `Quantidade vendida indisponível em ${r.resumo.semVolume} de ${r.resumo.elegiveis} anúncios.`,
        texto: 'Só o Mercado Livre informa (às vezes); nos demais a reputação usa só a nota e o volume mínimo não se aplica — dado ausente não é venda baixa.' });
    }
    const presumidos = r.elegiveis.filter((a) => a.anuncio.nota === null && a.anuncio.vendedorProprio).length;
    if (presumidos) {
      const plural = presumidos > 1;
      lista.push({ titulo: `${presumidos} anúncio${plural ? 's' : ''} sem nota ${plural ? 'ficaram' : 'ficou'} porque a própria loja vende.`,
        texto: 'A reputação desses é presumida (nota 4,5). Para descartá-los, desligue "Aceitar sem nota quando a própria loja vende" nos critérios.' });
    }
    if (esperouFila) lista.push({ titulo: 'Esta cotação esperou outra terminar.', texto: 'O navegador do servidor atende uma por vez.' });
    for (const m of coleta) lista.push({ titulo: 'Aviso da coleta:', texto: m });
    return lista;
  });

  /** Pontos que o critério somou no score final (parcial × peso efetivo). */
  protected contribuicao(a: Avaliado, k: 'preco' | 'entrega' | 'fornecedor' | 'marca'): number {
    const peso = this.resposta().resultado.resumo?.pesosEfetivos[k] ?? 0;
    return (a.parciais[k] * peso) / 100;
  }

  protected selos(a: Anuncio): { texto: string; classe: string }[] {
    const s: { texto: string; classe: string }[] = [];
    if (a.full) s.push({ texto: 'FULL', classe: 'cheio' });
    else if (a.freteGratis) s.push({ texto: 'Frete grátis', classe: '' });
    if (a.internacional) s.push({ texto: 'Internacional' + (a.pais ? ' · ' + a.pais : ''), classe: 'alerta' });
    if (a.vendedorProprio) s.push({ texto: 'Vendido pela loja', classe: '' });
    else if (a.lojaOficial) s.push({ texto: 'Loja oficial', classe: '' });
    if (a.nota !== null) s.push({ texto: a.nota.toFixed(1).replace('.', ',') + ' ★', classe: '' });
    if (a.vendidos) s.push({ texto: '+' + a.vendidos.toLocaleString('pt-BR') + ' vendidos', classe: '' });
    if (a.precoDe && a.precoDe > a.preco) {
      const off = Math.round((1 - a.preco / a.precoDe) * 100);
      if (off > 0) s.push({ texto: off + '% off', classe: 'off' });
    }
    return s;
  }

  /** "FULL" é a entrega expressa do Mercado Livre. */
  protected entrega(a: Anuncio): string {
    if (a.full) return 'FULL';
    return a.freteGratis ? 'Frete grátis' : 'Envio comum';
  }

  protected vendedor(a: Anuncio): string {
    if (a.vendedorProprio) return 'vendido por ' + a.fonte;
    if (a.vendedor) return 'vendido por ' + a.vendedor;
    return a.lojaOficial ? 'loja oficial' : 'vendedor não identificado';
  }

  protected origem(a: Anuncio): string {
    return a.internacional ? a.pais || 'Internacional' : 'Nacional';
  }
}
