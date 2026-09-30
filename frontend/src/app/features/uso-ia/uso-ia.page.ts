import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { mensagensDeErro } from '../../core/api';
import { Faixa } from '../../layout/faixa';
import { Icone } from '../../layout/icone';
import { ChamadaIa, EstadoModelo, PainelUsoIa, SituacaoModelo, UsoIaApi } from './uso-ia.api';

/**
 * Painel "Uso da IA": a cota de cada modelo da cadeia de fallback (do melhor para o pior), o que este servidor
 * gastou no dia do Google e as últimas chamadas. Atualiza sozinho a cada 15 s.
 *
 * Área técnica, fora do menu do helpdesk (30/09/2026): abre só por /swagger/uso-ia, ao lado da documentação da
 * API. Para o atendente o que importa é o aviso na revisão quando a leitura saiu de um modelo reserva.
 *
 * Só mostra o que passou por ESTE servidor. O Google conta por projeto: outra máquina ou o HTML v3.5 com a chave
 * antiga gastam a mesma cota sem aparecer aqui — a diferença aparece no painel do AI Studio.
 */
@Component({
  selector: 'ha-uso-ia',
  imports: [Faixa, Icone],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <ha-faixa sobretitulo="Área técnica · Gemini" titulo="Uso da IA"
      subtitulo="Cota de cada modelo da cadeia e o que este servidor gastou hoje" />
    <main class="container">
      <p class="uia-nota">
        Área técnica, fora do menu do helpdesk. Documentação da API (inclui <code>GET /api/uso-ia</code>, os dados
        desta página): <a href="swagger-ui.html" target="_blank" rel="noopener">Swagger</a>
      </p>
      @if (erro()) { <div class="status erro">{{ erro() }}</div> }
      @if (painel(); as p) {
        <section class="card">
          <header class="card-header">
            <h2>Hoje, no dia do Google</h2>
            <span class="card-sub">zera às {{ hora(p.viradaDoDia) }} · atualizado às {{ hora(p.agora, true) }}</span>
          </header>
          <div class="card-body">
            <div class="cot-kpis uia-kpis">
              <div><b>{{ p.hoje.leituras }}</b><span>leituras</span></div>
              <div><b>{{ p.hoje.requisicoes }}</b><span>requisições ao Google</span></div>
              <div><b [class.cot-kpi-desc]="p.hoje.picoPorMinuto >= limiteMinutoPrincipal()">{{ p.hoje.picoPorMinuto }}</b>
                <span>pico por minuto @if (p.hoje.momentoDoPico) { · {{ hora(p.hoje.momentoDoPico) }} }</span></div>
              <div><b>{{ milhar(p.hoje.tokensEntrada) }}</b><span>tokens de entrada</span></div>
              <div><b [class.cot-kpi-desc]="p.hoje.foraDoPrincipal > 0">{{ p.hoje.foraDoPrincipal }}</b><span>leituras em modelo reserva</span></div>
              <div><b [class.cot-kpi-desc]="p.hoje.erros > 0">{{ p.hoje.erros }}</b><span>recusas e erros</span></div>
            </div>
            <p class="uia-nota">
              Conta só o que passou por este servidor. O Google soma o projeto inteiro: se o painel do
              <a href="https://aistudio.google.com/rate-limit" target="_blank" rel="noopener">AI Studio</a> mostrar mais que isso,
              há outra coisa usando a mesma chave.
            </p>
          </div>
        </section>

        <section class="card">
          <header class="card-header">
            <h2>Cadeia de modelos</h2>
            <span class="card-sub">esgotou ou sobrecarregou → desce um degrau; volta a subir sozinho</span>
          </header>
          <div class="card-body uia-cadeia">
            @for (m of p.modelos; track m.modelo) {
              <div class="uia-modelo" [class.uia-fora]="m.estado === 'INDISPONIVEL'">
                <span class="uia-degrau">{{ m.degrau + 1 }}</span>
                <div class="uia-nome">
                  <b class="mono">{{ m.modelo }}</b>
                  <span class="uia-estado uia-{{ tom(m.estado) }}">{{ rotulo(m.estado) }}@if (m.ate) { · até {{ hora(m.ate) }} }</span>
                  @if (m.motivo) { <span class="uia-motivo">{{ m.motivo }}</span> }
                </div>
                <div class="uia-medida">
                  <span>Minuto</span>
                  <b class="mono">{{ m.requisicoesMinuto }}/{{ m.rpm }}</b>
                  <span class="mono">{{ milhar(m.tokensMinuto) }} / {{ milhar(m.tpm) }} tokens</span>
                </div>
                <div class="uia-medida uia-dia">
                  <span>Dia @if (m.rpdAprendido) { <em title="O Google informou este limite num 429">(limite do Google)</em> }</span>
                  <b class="mono">{{ m.requisicoesDia }}/{{ m.rpd }}</b>
                  <div class="uia-barra" [class.alerta]="fracao(m) >= 0.8"><i [style.width.%]="fracao(m) * 100"></i></div>
                </div>
                <div class="uia-medida">
                  <span>Hoje</span>
                  <span class="mono">{{ milhar(m.tokensEntradaDia) }} entrada · {{ milhar(m.tokensSaidaDia) }} saída</span>
                  <span class="mono">{{ m.errosDia }} recusa(s) · {{ m.pulosDia }} pulo(s)</span>
                </div>
              </div>
            }
          </div>
        </section>

        <section class="card">
          <header class="card-header">
            <h2>Últimas 24 horas</h2>
            <span class="card-sub">requisições por hora · vermelho = recusas e erros</span>
          </header>
          <div class="card-body">
            <div class="uia-horas">
              @for (h of p.ultimas24h; track h.inicio) {
                <div class="uia-hora" [title]="hora(h.inicio) + ': ' + h.requisicoes + ' requisição(ões), ' + h.erros + ' erro(s), ' + milhar(h.tokensEntrada) + ' tokens'">
                  <div class="uia-coluna">
                    <i [style.height.%]="altura(h.requisicoes - h.erros)"></i>
                    <i class="erro" [style.height.%]="altura(h.erros)"></i>
                  </div>
                  <span>{{ hora(h.inicio).slice(0, 2) }}</span>
                </div>
              }
            </div>
          </div>
        </section>

        <section class="card">
          <header class="card-header">
            <h2>Últimas chamadas</h2>
            <span class="card-sub">{{ p.ultimas.length }} mais recentes</span>
          </header>
          <div class="card-body">
            <div class="uia-chamadas">
              @for (c of p.ultimas; track $index) {
                <div class="uia-chamada">
                  <span class="mono">{{ dataHora(c.momento) }}</span>
                  <span class="mono">{{ c.modelo }}</span>
                  <span>{{ papel(c) }}</span>
                  <span class="uia-estado uia-{{ c.ocorrencia === 'OK' ? 'ok' : c.ocorrencia === 'ERRO' ? 'erro' : 'alerta' }}">
                    {{ ocorrencia(c) }}
                  </span>
                  <span class="mono">{{ c.tokensEntrada === null ? '—' : milhar(c.tokensEntrada) }} tk · {{ segundos(c.duracaoMs) }}</span>
                  <span class="uia-quem">{{ c.modo ?? '' }} · {{ c.arquivos }} arq. · {{ c.origem ?? '' }}</span>
                </div>
              } @empty {
                <div class="hist-vazio"><ha-icone nome="grafico" /> Nenhuma chamada registrada ainda.</div>
              }
            </div>
          </div>
        </section>
      } @else if (!erro()) {
        <div class="hist-vazio">Carregando…</div>
      }
    </main>
  `,
})
export class UsoIaPage {
  private readonly api = inject(UsoIaApi);

  protected readonly painel = signal<PainelUsoIa | null>(null);
  protected readonly erro = signal('');

  /** Pico por minuto que já encosta no limite do melhor modelo (pinta o número de alerta). */
  protected readonly limiteMinutoPrincipal = computed(() => this.painel()?.modelos[0]?.rpm ?? Infinity);
  private readonly maxHora = computed(() => Math.max(1, ...(this.painel()?.ultimas24h.map((h) => h.requisicoes) ?? [1])));

  constructor() {
    this.carregar();
    const t = setInterval(() => this.carregar(), 15_000);
    inject(DestroyRef).onDestroy(() => clearInterval(t));
  }

  private async carregar(): Promise<void> {
    try {
      this.painel.set(await firstValueFrom(this.api.painel()));
      this.erro.set('');
    } catch (e) {
      this.erro.set((await mensagensDeErro(e)).join(' '));
    }
  }

  protected rotulo(e: EstadoModelo): string {
    return {
      DISPONIVEL: 'Disponível',
      ESFRIANDO: 'Esfriando (sobrecarga recente)',
      LIMITE_LOCAL_MINUTO: 'Limite do minuto',
      LIMITE_LOCAL_DIA: 'Limite do dia',
      SEM_COTA_MINUTO: 'Google recusou (minuto)',
      SEM_COTA_DIA: 'Google recusou (dia)',
      INDISPONIVEL: 'Fora da cadeia',
    }[e];
  }

  protected tom(e: EstadoModelo): 'ok' | 'alerta' | 'erro' {
    if (e === 'DISPONIVEL') return 'ok';
    if (e === 'ESFRIANDO' || e === 'LIMITE_LOCAL_MINUTO' || e === 'SEM_COTA_MINUTO') return 'alerta';
    return 'erro';
  }

  protected papel(c: ChamadaIa): string {
    return { PRINCIPAL: 'Principal', RESERVA: 'Reserva em paralelo', DEGRAU: 'Desceu de degrau', REPETICAO: 'Repetição' }[c.papel];
  }

  protected ocorrencia(c: ChamadaIa): string {
    const nomes = {
      OK: 'OK',
      SOBRECARGA: 'Sobrecarga',
      COTA_MINUTO: 'Sem cota (minuto)',
      COTA_DIA: 'Sem cota (dia)',
      INDISPONIVEL: 'Modelo inexistente',
      ERRO: 'Erro',
    };
    return nomes[c.ocorrencia] + (c.status && c.status !== 200 ? ` ${c.status}` : '');
  }

  protected fracao(m: SituacaoModelo): number {
    return m.rpd > 0 ? Math.min(1, m.requisicoesDia / m.rpd) : 1;
  }

  protected altura(n: number): number {
    return (Math.max(0, n) / this.maxHora()) * 100;
  }

  protected hora(iso: string, comSegundos = false): string {
    return new Date(iso).toLocaleTimeString('pt-BR', { hour: '2-digit', minute: '2-digit', second: comSegundos ? '2-digit' : undefined });
  }

  protected dataHora(iso: string): string {
    const d = new Date(iso);
    return d.toLocaleDateString('pt-BR', { day: '2-digit', month: '2-digit' }) + ' ' + this.hora(iso, true);
  }

  protected milhar(n: number): string {
    return n.toLocaleString('pt-BR');
  }

  protected segundos(ms: number): string {
    return (ms / 1000).toLocaleString('pt-BR', { maximumFractionDigits: 1 }) + ' s';
  }
}
