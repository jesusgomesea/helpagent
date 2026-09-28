import { ChangeDetectionStrategy, Component, computed, effect, inject, signal } from '@angular/core';
import { Avisos } from '../core/avisos';
import { LOGOS_GRUPO, MarcaService } from '../core/marca';

/**
 * Overlay enquanto a IA lê os documentos ou o PDF é montado: as logos do grupo passam em esteira,
 * começando pela marca do visual ativo, e um cronômetro mostra há quanto tempo a ação está rodando
 * (espera com número é percebida como mais curta que espera muda). Com "reduzir movimento" ligado
 * no sistema, a esteira fica parada.
 */
@Component({
  selector: 'ha-processando',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (avisos.processando(); as status) {
      <div class="overlay" role="status" aria-live="polite">
        <div class="build-box">
          <div class="build-title">Construindo<span class="dots"><i>.</i><i>.</i><i>.</i></span></div>
          <div class="esteira" aria-hidden="true">
            <!-- duas cópias da faixa: quando a primeira sai pela esquerda, a segunda já ocupa o lugar -->
            @for (copia of [0, 1]; track copia) {
              <div class="esteira-faixa">
                @for (l of logos(); track $index) {
                  <img [src]="l.arquivo" [class.branca]="l.filtrarParaBranco" alt="">
                }
              </div>
            }
          </div>
          <div class="build-status">{{ status }}</div>
          <div class="build-tempo">{{ segundos() }} s</div>
        </div>
      </div>
    }
  `,
})
export class Processando {
  protected readonly avisos = inject(Avisos);
  private readonly marca = inject(MarcaService);
  protected readonly segundos = signal(0);

  /**
   * Logos do grupo, a marca do visual ativo na frente. A sequência vai duas vezes na faixa para ela
   * ser sempre mais larga que a caixa — senão aparece um buraco no fim do loop.
   */
  protected readonly logos = computed(() => {
    const ativa = this.marca.info().nome;
    const ordem = [...LOGOS_GRUPO].sort((a, b) => Number(b.nome === ativa) - Number(a.nome === ativa));
    return [...ordem, ...ordem];
  });

  constructor() {
    // Cronômetro: zera ao abrir o overlay, conta enquanto ele estiver aberto.
    effect((onCleanup) => {
      if (!this.avisos.processando()) return;
      this.segundos.set(0);
      const id = setInterval(() => this.segundos.update((s) => s + 1), 1000);
      onCleanup(() => clearInterval(id));
    });
  }
}
