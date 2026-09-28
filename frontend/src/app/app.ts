import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { MarcaService } from './core/marca';
import { Cabecalho } from './layout/cabecalho';
import { Processando } from './layout/processando';
import { Toasts } from './layout/toasts';

/**
 * Casca da aplicação: overlay de carregamento, cabeçalho, página da rota, rodapé e toasts.
 * Cada página desenha a própria faixa de abertura (ha-faixa) e o seu <main class="container">.
 */
@Component({
  selector: 'ha-root',
  imports: [RouterOutlet, Cabecalho, Processando, Toasts],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <ha-processando />
    <ha-cabecalho />
    <router-outlet />
    <footer class="rodape">
      <img [src]="marca.info().logoBranca" [class.branca]="marca.info().filtrarParaBranco" alt="">
      <span>Help-Agent · TI R Damásio</span>
    </footer>
    <ha-toasts />
  `,
})
export class App {
  protected readonly marca = inject(MarcaService);
}
