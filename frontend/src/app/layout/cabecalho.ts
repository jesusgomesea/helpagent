import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { catchError, map, of } from 'rxjs';
import { Api } from '../core/api';
import { RouterLink, RouterLinkActive } from '@angular/router';
import { MARCAS, MarcaService } from '../core/marca';
import { Recursos } from '../core/recursos';
import { CestaCotacao } from '../features/cotacao/cesta.store';
import { Icone } from './icone';

/**
 * Cabeçalho fixo: logo da marca ativa, navegação e o seletor de visual (Damásio × TD). Fora da produção
 * (homologação), uma faixa acima avisa em que ambiente se está.
 */
@Component({
  selector: 'ha-cabecalho',
  imports: [RouterLink, RouterLinkActive, Icone],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (ambiente()) {
      <div class="faixa-ambiente" role="status">
        Ambiente de {{ ambiente() === 'homologacao' ? 'homologação' : ambiente() }} · dados de teste, separados da produção
      </div>
    }
    <header class="header">
      <a class="logo" routerLink="/" aria-label="Início">
        <img [src]="marca.info().logoBranca" [class.branca]="marca.info().filtrarParaBranco" [alt]="marca.info().nome">
        <span class="logo-sep"></span>
        <span class="logo-sub">Help-Agent · Orçamentos</span>
      </a>
      <nav class="nav">
        <a routerLink="/" routerLinkActive="ativo" [routerLinkActiveOptions]="{ exact: true }" title="Novo orçamento" aria-label="Novo orçamento">
          <ha-icone nome="novo" [tamanho]="15" /><span>Novo<span class="so-desktop"> orçamento</span></span>
        </a>
        @if (recursos.cotacao()) {
          <a routerLink="/cotacao" routerLinkActive="ativo" title="Cotação em lojas online" aria-label="Cotação">
            <ha-icone nome="busca" [tamanho]="15" /><span>Cotação</span>
            @if (cesta.itens().length) { <b class="nav-contador" title="Itens no orçamento por cotação">{{ cesta.itens().length }}</b> }
          </a>
        }
        <a routerLink="/lojas" routerLinkActive="ativo" title="Lojas" aria-label="Lojas">
          <ha-icone nome="loja" [tamanho]="15" /><span>Lojas</span>
        </a>
        <!-- Histórico por último, colado ao seletor de visual (pedido do helpdesk, 29/09/2026) -->
        <a routerLink="/historico" routerLinkActive="ativo" title="Histórico" aria-label="Histórico">
          <ha-icone nome="historico" [tamanho]="15" /><span>Histórico</span>
        </a>
      </nav>
      <div class="seletor-marca" role="radiogroup" aria-label="Visual">
        @for (m of marcas; track m.id) {
          <button role="radio" [attr.aria-checked]="marca.marca() === m.id" [class.ativo]="marca.marca() === m.id"
            (click)="marca.definir(m.id)" [title]="'Visual ' + m.nome">
            <img [src]="m.logoBranca" [class.branca]="m.filtrarParaBranco" [alt]="m.nome">
          </button>
        }
      </div>
    </header>
  `,
})
export class Cabecalho {
  protected readonly marca = inject(MarcaService);
  protected readonly cesta = inject(CestaCotacao);
  /** A cotação some do menu quando o servidor não a oferece. O painel de uso da IA não fica no menu: é
   *  área técnica, em /swagger/uso-ia (30/09/2026). */
  protected readonly recursos = inject(Recursos);
  protected readonly ambiente = toSignal(inject(Api).parametros().pipe(map((p) => p.ambiente ?? ''), catchError(() => of(''))), {
    initialValue: '',
  });
  protected readonly marcas = Object.values(MARCAS);
}
