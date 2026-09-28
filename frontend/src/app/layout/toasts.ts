import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { Avisos } from '../core/avisos';

/** Mensagens rápidas no canto da tela (disparadas por Avisos.toast). */
@Component({
  selector: 'ha-toasts',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="toasts" aria-live="polite">
      @for (t of avisos.toasts(); track t.id) {
        <div class="toast"><span class="toast-ic">{{ t.icone }}</span><span>{{ t.mensagem }}</span></div>
      }
    </div>
  `,
})
export class Toasts {
  protected readonly avisos = inject(Avisos);
}
