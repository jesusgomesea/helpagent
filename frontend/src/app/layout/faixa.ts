import { ChangeDetectionStrategy, Component, input } from '@angular/core';

/**
 * Abertura de cada página no padrão "hero" do Design System R Damásio: sobretítulo em caixa-alta com o
 * traço vermelho, título grande e um texto de apoio. Conteúdo extra (ex.: o indicador de etapas) entra por projeção.
 */
@Component({
  selector: 'ha-faixa',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="faixa">
      <div class="sobretitulo">{{ sobretitulo() }}</div>
      <h1>{{ titulo() }}</h1>
      @if (subtitulo()) { <p>{{ subtitulo() }}</p> }
      <ng-content />
    </div>
  `,
})
export class Faixa {
  readonly titulo = input.required<string>();
  readonly subtitulo = input<string>();
  /** Linha pequena acima do título ("Helpdesk · Orçamentos"). */
  readonly sobretitulo = input('Help-Agent');
}
