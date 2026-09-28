import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { Icone } from './icone';

/** Indicador 1 Imagens → 2 Extração → 3 Revisão → 4 PDF (existia no cabeçalho da v3.5). */
@Component({
  selector: 'ha-etapas',
  imports: [Icone],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <ol class="etapas" aria-label="Etapas">
      @for (e of nomes; track e; let i = $index) {
        <li [class.feita]="i + 1 < atual()" [class.atual]="i + 1 === atual()" [attr.aria-current]="i + 1 === atual() ? 'step' : null">
          <span class="etapa-num">
            @if (i + 1 < atual()) { <ha-icone nome="check" [tamanho]="13" /> } @else { {{ i + 1 }} }
          </span>
          <span class="etapa-nome">{{ e }}</span>
        </li>
      }
    </ol>
  `,
})
export class Etapas {
  readonly atual = input.required<number>();
  protected readonly nomes = ['Imagens', 'Extração', 'Revisão', 'PDF'];
}
