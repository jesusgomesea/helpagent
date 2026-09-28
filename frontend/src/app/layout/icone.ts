import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

/** Ícones de traço (no lugar dos emojis da v3.5), no mesmo espírito dos ícones do site. */
const ICONES = {
  clipe: 'M21.4 11.1 12.2 20.3a6 6 0 0 1-8.5-8.5l9.2-9.2a4 4 0 0 1 5.7 5.7l-9.2 9.2a2 2 0 0 1-2.8-2.8l8.5-8.5',
  enviar: 'M5 12h14M13 6l6 6-6 6',
  baixar: 'M12 4v11M7 10l5 5 5-5M5 20h14',
  fechar: 'M6 6l12 12M18 6 6 18',
  chamado: 'M9 4h6a1 1 0 0 1 1 1v1H8V5a1 1 0 0 1 1-1ZM8 6H6a1 1 0 0 0-1 1v13a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1V7a1 1 0 0 0-1-1h-2M9 12h6M9 16h4',
  recibo: 'M6 3h12v18l-3-2-3 2-3-2-3 2V3ZM9 8h6M9 12h6M9 16h3',
  historico: 'M3 12a9 9 0 1 0 3-6.7L3 8M3 3v5h5M12 7v5l3 3',
  novo: 'M12 5v14M5 12h14',
  pdf: 'M14 3H6a1 1 0 0 0-1 1v16a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1V8l-5-5ZM14 3v5h5M9 13h6M9 17h6',
  check: 'M5 12l5 5L20 7',
  enviarArquivo: 'M12 20V9M7 14l5-5 5 5M5 4h14',
  loja: 'M4 10v10h16V10M3 10l2-6h14l2 6H3ZM9 20v-6h6v6',
  alerta: 'M12 3 2 20h20L12 3ZM12 10v4M12 17h.01',
  busca: 'M11 4a7 7 0 1 0 0 14 7 7 0 0 0 0-14ZM20 20l-4-4',
} as const;

export type NomeIcone = keyof typeof ICONES;

@Component({
  selector: 'ha-icone',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'icone-svg', 'aria-hidden': 'true' },
  template: `
    <svg viewBox="0 0 24 24" [attr.width]="tamanho()" [attr.height]="tamanho()" fill="none" stroke="currentColor"
      stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path [attr.d]="d()" /></svg>
  `,
})
export class Icone {
  readonly nome = input.required<NomeIcone>();
  readonly tamanho = input(18);
  protected readonly d = computed(() => ICONES[this.nome()]);
}
