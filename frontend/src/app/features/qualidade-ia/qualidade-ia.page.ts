import { HttpClient } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { mensagensDeErro } from '../../core/api';
import { Faixa } from '../../layout/faixa';
import { Icone } from '../../layout/icone';

// Tipos de GET /api/qualidade-ia (QualidadeIaService.Relatorio no backend, mesmos nomes de campo).
interface Linha {
  nome: string;
  orcamentos: number;
  comparados: number;
  corrigidos: number;
  /** 0 a 1 */
  acerto: number;
  maisCorrigido: string | null;
}

interface Exemplo {
  momento: string;
  campo: string;
  item: number;
  fornecedor: string | null;
  lido: string | null;
  confirmado: string | null;
  modelo: string;
  orcamentoId: number | null;
}

interface Relatorio {
  dias: number;
  leituras: number;
  orcamentosComparados: number;
  semCorrecao: number;
  porCampo: Linha[];
  porModelo: Linha[];
  porFornecedor: Linha[];
  ultimas: Exemplo[];
}

/** Nome amigável de cada campo comparado (ComparacaoLeitura no backend). */
const CAMPOS: Record<string, string> = {
  titulo: 'Título',
  observacao: 'Observação',
  chamado: 'Chamado',
  loja: 'Loja',
  total: 'Total',
  produto: 'Produto',
  descricao: 'Descrição',
  quantidade: 'Quantidade',
  valor_unitario: 'Valor unitário',
  fornecedor: 'Fornecedor',
  numero_de_itens: 'Número de itens',
};

/**
 * Qualidade da IA (área técnica, /swagger/qualidade-ia, 30/09/2026): compara o que a IA leu com o que o atendente
 * confirmou ao gerar cada orçamento e mostra onde ela mais erra — por campo, por modelo e por fornecedor — com os
 * exemplos mais recentes. É o insumo para ajustar o prompt (prompts/extracao.txt) com dado; nada aqui muda a leitura
 * sozinho.
 */
@Component({
  selector: 'ha-qualidade-ia',
  imports: [Faixa, Icone, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <ha-faixa sobretitulo="Área técnica · Gemini" titulo="Qualidade da IA"
      subtitulo="O que os atendentes corrigem na leitura antes de gerar o orçamento" />
    <main class="container">
      <p class="uia-nota">
        Área técnica, fora do menu. Cada orçamento gerado a partir de uma leitura por IA é comparado campo a campo com o
        que a IA leu. Uso e cota: <a routerLink="/swagger/uso-ia">Uso da IA</a>
      </p>
      <div class="seg" role="group" aria-label="Período">
        @for (d of periodos; track d) {
          <button type="button" [class.ativo]="dias() === d" (click)="mudar(d)">{{ d }} dias</button>
        }
      </div>
      @if (erro()) { <div class="status erro">{{ erro() }}</div> }
      @if (r(); as r) {
        <section class="card">
          <header class="card-header"><h2>Resumo</h2><span class="card-sub">últimos {{ r.dias }} dias</span></header>
          <div class="card-body">
            <div class="cot-kpis uia-kpis qia-kpis">
              <div><b>{{ r.leituras }}</b><span>leituras pela IA</span></div>
              <div><b>{{ r.orcamentosComparados }}</b><span>viraram orçamento (comparados)</span></div>
              <div><b>{{ pct(r.orcamentosComparados ? r.semCorrecao / r.orcamentosComparados : 0) }}</b><span>saíram sem nenhuma correção</span></div>
              <div><b [class.cot-kpi-desc]="true">{{ r.porCampo[0] ? rotulo(r.porCampo[0].nome) : '—' }}</b><span>campo mais corrigido</span></div>
            </div>
            @if (!r.orcamentosComparados) {
              <p class="uia-nota">Ainda não há orçamentos gerados a partir de leitura por IA neste período. Os números aparecem a
                partir do próximo orçamento gerado com "Extrair dados com IA".</p>
            }
          </div>
        </section>

        <section class="card">
          <header class="card-header"><h2>Por campo</h2><span class="card-sub">acerto = a IA leu igual ao confirmado</span></header>
          <div class="card-body">
            @for (l of r.porCampo; track l.nome) {
              <div class="qia-linha">
                <span class="qia-nome">{{ rotulo(l.nome) }}</span>
                <div class="uia-barra" [class.alerta]="l.acerto < 0.8"><i [style.width.%]="l.acerto * 100"></i></div>
                <span class="mono">{{ pct(l.acerto) }}</span>
                <span class="mono qia-conta">{{ l.corrigidos }} de {{ l.comparados }} corrigido(s)</span>
              </div>
            } @empty { <div class="hist-vazio"><ha-icone nome="grafico" /> Sem dados no período.</div> }
          </div>
        </section>

        <div class="qia-duas">
          <section class="card">
            <header class="card-header"><h2>Por modelo</h2></header>
            <div class="card-body">
              @for (l of r.porModelo; track l.nome) {
                <div class="qia-linha">
                  <span class="qia-nome mono">{{ l.nome }}</span>
                  <div class="uia-barra" [class.alerta]="l.acerto < 0.8"><i [style.width.%]="l.acerto * 100"></i></div>
                  <span class="mono">{{ pct(l.acerto) }}</span>
                  <span class="mono qia-conta">{{ l.orcamentos }} orç.</span>
                </div>
              } @empty { <div class="hist-vazio">Sem dados no período.</div> }
            </div>
          </section>
          <section class="card">
            <header class="card-header"><h2>Por fornecedor</h2><span class="card-sub">campos das linhas</span></header>
            <div class="card-body">
              @for (l of r.porFornecedor; track l.nome) {
                <div class="qia-linha">
                  <span class="qia-nome">{{ l.nome }}</span>
                  <div class="uia-barra" [class.alerta]="l.acerto < 0.8"><i [style.width.%]="l.acerto * 100"></i></div>
                  <span class="mono">{{ pct(l.acerto) }}</span>
                  <span class="mono qia-conta">@if (l.maisCorrigido) { mais: {{ rotulo(l.maisCorrigido) }} }</span>
                </div>
              } @empty { <div class="hist-vazio">Sem dados no período.</div> }
            </div>
          </section>
        </div>

        <section class="card">
          <header class="card-header"><h2>Últimas correções</h2><span class="card-sub">o que a IA leu → o que foi confirmado</span></header>
          <div class="card-body">
            @for (e of r.ultimas; track $index) {
              <div class="qia-exemplo">
                <span class="mono">{{ data(e.momento) }}</span>
                <span><b>{{ rotulo(e.campo) }}</b>@if (e.item) { · linha {{ e.item }} }@if (e.fornecedor) { · {{ e.fornecedor }} }</span>
                <span class="qia-lido">{{ e.lido || '(vazio)' }}</span>
                <span class="qia-seta">→</span>
                <span class="qia-final">{{ e.confirmado || '(vazio)' }}</span>
                <span class="mono qia-conta">{{ e.modelo }}</span>
              </div>
            } @empty { <div class="hist-vazio">Nenhuma correção registrada.</div> }
          </div>
        </section>
      } @else if (!erro()) {
        <div class="hist-vazio">Carregando…</div>
      }
    </main>
  `,
})
export class QualidadeIaPage {
  private readonly http = inject(HttpClient);

  protected readonly periodos = [7, 30, 90, 365];
  protected readonly dias = signal(90);
  protected readonly r = signal<Relatorio | null>(null);
  protected readonly erro = signal('');

  constructor() {
    this.carregar();
  }

  protected mudar(d: number): void {
    this.dias.set(d);
    this.carregar();
  }

  private async carregar(): Promise<void> {
    try {
      this.r.set(await firstValueFrom(this.http.get<Relatorio>('/api/qualidade-ia', { params: { dias: this.dias() } })));
      this.erro.set('');
    } catch (e) {
      this.erro.set((await mensagensDeErro(e)).join(' '));
    }
  }

  protected rotulo(campo: string): string {
    return CAMPOS[campo] ?? campo;
  }

  protected pct(v: number): string {
    return Math.round(v * 100) + '%';
  }

  protected data(iso: string): string {
    const d = new Date(iso);
    return d.toLocaleDateString('pt-BR', { day: '2-digit', month: '2-digit' }) + ' ' +
      d.toLocaleTimeString('pt-BR', { hour: '2-digit', minute: '2-digit' });
  }
}
