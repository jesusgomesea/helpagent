import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { firstValueFrom } from 'rxjs';
import { Api, mensagensDeErro, salvarArquivo } from '../../core/api';
import { Avisos } from '../../core/avisos';
import { Recursos } from '../../core/recursos';
import { DICA_MODO, GerarOrcamentoRequest, MODOS, ROTULO_MODO } from '../../core/modelos';
import { Etapas } from '../../layout/etapas';
import { Faixa } from '../../layout/faixa';
import { Composer } from './composer';
import { Documentos } from './documentos';
import { OrcamentoStore } from './orcamento.store';
import { Revisao } from './revisao';

@Component({
  selector: 'ha-novo-orcamento',
  imports: [Faixa, Etapas, Composer, Documentos, Revisao],
  providers: [OrcamentoStore],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <ha-faixa sobretitulo="Helpdesk · Orçamentos" titulo="Novo orçamento" subtitulo="Do chamado ao impresso oficial, com leitura por IA">
      <ha-etapas [atual]="store.etapa()" />
    </ha-faixa>
    <!-- Na revisão a página alarga para caber os documentos originais ao lado do formulário. -->
    <main class="container" [class.largo]="mostrarRevisao()">
    <div class="modo">
      <span class="rotulo-secao">Entrada de dados</span>
      <div class="seg" role="group" aria-label="Tipo de requisição">
        @for (m of modos; track m) {
          <button [class.ativo]="store.modo() === m" (click)="store.setModo(m)" [title]="dicas[m]">{{ rotulos[m] }}</button>
        }
      </div>
    </div>

    @if (!recursos.ia() && !mostrarRevisao()) {
      <!-- servidor sem IA (helpagent.recursos.ia=false): o fluxo manual é o caminho -->
      <div class="status alerta">
        A leitura por IA está desligada neste servidor. Anexe os documentos e preencha o orçamento à mão.
        <button class="btn-link" (click)="preencherManualmente()">Preencher manualmente</button>
      </div>
    }
    <ha-composer [extraindo]="extraindo()" (extrair)="extrair()" />

    @if (erroExtracao().length) {
      <div class="status erro">
        <ul>@for (e of erroExtracao(); track $index) { <li>{{ e }}</li> }</ul>
        @if (!mostrarRevisao()) {
          <button class="btn-link" (click)="preencherManualmente()">Preencher manualmente, sem IA</button>
        }
      </div>
    }

    @if (mostrarRevisao() && lojas() && parametros()) {
      <div class="revisao-dividida">
      <ha-documentos [chamados]="store.chamados()" [orcamentos]="store.orcamentos()" />
      <ha-revisao
        [lojas]="lojas()!"
        [parametros]="parametros()!"
        [extracao]="store.extracao()"
        [gerando]="gerando()"
        [erros]="erroGeracao()"
        [sucesso]="sucesso()"
        (gerar)="gerar($event)"
        (novo)="novo()" />
      </div>
    }
    </main>
  `,
})
export class NovoOrcamentoPage {
  protected readonly store = inject(OrcamentoStore);
  protected readonly modos = MODOS;
  protected readonly rotulos = ROTULO_MODO;
  protected readonly dicas = DICA_MODO;
  private readonly api = inject(Api);
  protected readonly recursos = inject(Recursos);
  private readonly avisos = inject(Avisos);

  protected readonly lojas = toSignal(this.api.lojas());
  protected readonly parametros = toSignal(this.api.parametros());

  protected readonly mostrarRevisao = signal(false);
  protected readonly extraindo = signal(false);
  protected readonly gerando = signal(false);
  protected readonly erroExtracao = signal<string[]>([]);
  protected readonly erroGeracao = signal<string[]>([]);
  protected readonly sucesso = signal<string | null>(null);

  protected async extrair(): Promise<void> {
    this.extraindo.set(true);
    this.erroExtracao.set([]);
    this.store.etapa.set(2);
    const lendo = this.store.exigeChamado() ? 'IA lendo o chamado e os orçamentos' : 'IA lendo os orçamentos';
    this.avisos.iniciar('Enviando os arquivos');
    try {
      const r = await firstValueFrom(
        this.api.extrair(this.store.modo(), this.store.chamadosParaIa(), this.store.orcamentos(), (fase, pct) =>
          this.avisos.iniciar(fase === 'lendo' ? lendo : 'Enviando os arquivos' + (pct !== undefined ? ' · ' + pct + '%' : '')),
        ),
      );
      this.store.extracao.set(r);
      this.mostrarRevisao.set(true);
      this.store.etapa.set(3);
      const n = this.store.orcamentos().length;
      const tempo = r.doCache ? 'na hora (mesmos arquivos de antes)' : 'em ' + (r.duracaoMs / 1000).toFixed(1).replace('.', ',') + ' s';
      this.avisos.toast(`Dados extraídos ${tempo}${n > 1 ? ` · ${n} orçamentos consolidados` : ''}. Revise antes de gerar.`);
    } catch (e) {
      this.erroExtracao.set(await mensagensDeErro(e));
      this.store.etapa.set(1);
    } finally {
      this.extraindo.set(false);
      this.avisos.terminar();
    }
  }

  protected async gerar(dados: GerarOrcamentoRequest): Promise<void> {
    this.gerando.set(true);
    this.erroGeracao.set([]);
    this.sucesso.set(null);
    this.store.etapa.set(4);
    this.avisos.iniciar('Montando o impresso e anexando os documentos');
    try {
      const pdf = await firstValueFrom(this.api.gerar(dados, this.store.chamados(), this.store.orcamentos()));
      salvarArquivo(pdf);
      const c = this.store.chamados().length;
      this.sucesso.set(`✓ PDF baixado! Impresso completo: ${c ? `${c} chamado(s) + ` : ''}${this.store.orcamentos().length} orçamento(s) anexado(s).`);
      this.store.pdfGerado.set(true);
    } catch (e) {
      this.erroGeracao.set(await mensagensDeErro(e));
      this.store.etapa.set(3);
    } finally {
      this.gerando.set(false);
      this.avisos.terminar();
    }
  }

  /** Permite preencher à mão sem IA (ex.: IA fora do ar). */
  protected preencherManualmente(): void {
    this.mostrarRevisao.set(true);
    this.store.etapa.set(3);
  }

  protected novo(): void {
    this.store.reiniciar();
    this.mostrarRevisao.set(false);
    this.sucesso.set(null);
    this.erroGeracao.set([]);
    scrollTo({ top: 0, behavior: 'smooth' });
  }
}
