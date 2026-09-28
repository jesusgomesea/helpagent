import { ChangeDetectionStrategy, Component, ElementRef, inject, input, output, signal, viewChild } from '@angular/core';
import { criarAnexo, liberarAnexo, textoParaImagem } from '../../core/arquivos';
import { Avisos } from '../../core/avisos';
import { Anexo } from '../../core/modelos';
import { Icone } from '../../layout/icone';
import { Destino, OrcamentoStore } from './orcamento.store';

/** Card 1: colar (Ctrl+V), anexar ou digitar o chamado e os orçamentos. */
@Component({
  selector: 'ha-composer',
  imports: [Icone],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="card">
      <header class="card-header">
        <span class="card-num">1</span>
        <h2>Upload das imagens</h2>
        <span class="card-sub">{{ store.exigeChamado() ? 'chamado + orçamento(s)' : 'apenas orçamento(s) — chamado opcional' }}</span>
      </header>
      <div class="card-body">
        <div class="anexos">
          @for (a of store.chamados(); track a.id; let i = $index) {
            <div class="chip">
              <div class="chip-thumb">@if (a.tipo === 'imagem') { <img [src]="a.previewUrl" alt=""> } @else { <ha-icone nome="chamado" /> }</div>
              <div class="chip-info">
                <div class="chip-name">{{ store.chamados().length > 1 ? 'Chamado ' + (i + 1) : 'Chamado' }}</div>
                <div class="chip-sub">{{ a.tipo }} pronto</div>
              </div>
              <button class="chip-x" (click)="store.remover('chamado', a.id)" aria-label="Remover chamado"><ha-icone nome="fechar" [tamanho]="16" /></button>
            </div>
          }
          @for (a of store.orcamentos(); track a.id; let i = $index) {
            <div class="chip">
              <div class="chip-thumb">@if (a.tipo === 'imagem') { <img [src]="a.previewUrl" alt=""> } @else { <ha-icone nome="recibo" /> }</div>
              <div class="chip-info">
                <div class="chip-name">Orçamento {{ i + 1 }}</div>
                <div class="chip-sub">{{ a.tipo }} pronto</div>
              </div>
              <button class="chip-x" (click)="store.remover('orc', a.id)" aria-label="Remover orçamento"><ha-icone nome="fechar" [tamanho]="16" /></button>
            </div>
          }
        </div>

        <div class="composer" (paste)="aoColar($event)">
          <textarea #texto rows="3" [value]="textoAtual()" (input)="textoAtual.set(texto.value)"
            (keydown.control.enter)="adicionar(); $event.preventDefault()"
            (keydown.meta.enter)="adicionar(); $event.preventDefault()"
            placeholder="Cole a imagem (Ctrl V) — ex.: recorte do Win+Shift+S — ou digite / cole o texto do documento…"></textarea>

          @if (pendente(); as p) {
            <div class="pend-card">
              @if (p.tipo === 'imagem') { <img [src]="p.previewUrl" alt=""> }
              <span class="pend-tag">{{ p.tipo === 'PDF' ? 'PDF anexado' : 'Imagem' }}</span>
              <button class="pend-x" (click)="limparPendente()" aria-label="Remover"><ha-icone nome="fechar" [tamanho]="14" /></button>
            </div>
          }

          <div class="composer-bar">
            <button class="icone" title="Anexar arquivo" aria-label="Anexar arquivo" (click)="arquivo.click()"><ha-icone nome="clipe" [tamanho]="20" /></button>
            <input #arquivo type="file" accept="image/*,application/pdf" hidden (change)="aoEscolher(arquivo)">
            <div class="seg" role="group" aria-label="Destino do item">
              @if (store.exigeChamado()) {
                <button [class.ativo]="store.destino() === 'chamado'" (click)="setDestino('chamado')">Chamado</button>
              }
              <button [class.ativo]="store.destino() === 'orc'" (click)="setDestino('orc')">Orçamento</button>
            </div>
            <button class="enviar" title="Adicionar (Ctrl+Enter)" aria-label="Adicionar" (click)="adicionar()"><ha-icone nome="enviar" /></button>
          </div>
        </div>
        <p class="dica">cole imagem com <kbd>Ctrl V</kbd> · Enter quebra linha · <kbd>Ctrl Enter</kbd> adiciona</p>

        <button class="btn-primario" [disabled]="!store.prontoParaExtrair() || extraindo()" (click)="extrair.emit()">
          Extrair dados com IA
        </button>
        @if (!store.prontoParaExtrair()) { <p class="falta">{{ oQueFalta() }}</p> }
      </div>
    </section>
  `,
})
export class Composer {
  protected readonly store = inject(OrcamentoStore);
  private readonly avisos = inject(Avisos);

  readonly extraindo = input(false);
  readonly extrair = output<void>();

  protected readonly textoAtual = signal('');
  protected readonly pendente = signal<Anexo | null>(null);
  private readonly texto = viewChild.required<ElementRef<HTMLTextAreaElement>>('texto');

  /** Diz por que o botão de extração está desabilitado, em vez de só deixá-lo cinza. */
  protected oQueFalta(): string {
    const semOrc = this.store.orcamentos().length === 0;
    if (!this.store.exigeChamado()) return 'Adicione ao menos 1 orçamento para extrair.';
    const semCham = this.store.chamados().length === 0;
    if (semCham && semOrc) return 'Adicione o chamado e ao menos 1 orçamento para extrair.';
    return semCham ? 'Falta adicionar o chamado.' : 'Falta adicionar ao menos 1 orçamento.';
  }

  protected setDestino(d: Destino): void {
    this.store.destino.set(d);
  }

  protected async aoColar(e: ClipboardEvent): Promise<void> {
    for (const item of Array.from(e.clipboardData?.items ?? [])) {
      if (item.kind === 'file' && item.type.startsWith('image/')) {
        const blob = item.getAsFile();
        if (!blob) continue;
        e.preventDefault();
        await this.definirPendente(blob, 'imagem-colada.png');
        return;
      }
    }
  }

  protected async aoEscolher(input: HTMLInputElement): Promise<void> {
    const f = input.files?.[0];
    input.value = '';
    if (f) await this.definirPendente(f, f.name);
  }

  protected limparPendente(): void {
    const p = this.pendente();
    if (p) liberarAnexo(p);
    this.pendente.set(null);
  }

  protected async adicionar(): Promise<void> {
    let anexo = this.pendente();
    const texto = this.textoAtual().trim();
    if (!anexo && !texto) {
      this.avisos.toast('Cole uma imagem ou escreva algo', '⚠');
      return;
    }
    if (!anexo) anexo = await criarAnexo(await textoParaImagem(texto), 'texto.png');
    this.avisos.toast(this.store.adicionar(anexo));
    this.pendente.set(null);
    this.textoAtual.set('');
    if (matchMedia('(min-width: 721px)').matches) this.texto().nativeElement.focus();
  }

  private async definirPendente(arquivo: Blob, nome: string): Promise<void> {
    try {
      this.limparPendente();
      this.pendente.set(await criarAnexo(arquivo, nome));
    } catch (e) {
      this.avisos.toast((e as Error).message, '⚠');
    }
  }
}
