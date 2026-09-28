import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { RouterLink, RouterLinkActive } from '@angular/router';
import { MARCAS, MarcaService } from '../core/marca';
import { Icone } from './icone';

/** Cabeçalho fixo: logo da marca ativa, navegação e o seletor de visual (Damásio × TD). */
@Component({
  selector: 'ha-cabecalho',
  imports: [RouterLink, RouterLinkActive, Icone],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
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
        <a routerLink="/historico" routerLinkActive="ativo" title="Histórico" aria-label="Histórico">
          <ha-icone nome="historico" [tamanho]="15" /><span>Histórico</span>
        </a>
        <a routerLink="/cotacao" routerLinkActive="ativo" title="Cotação no Mercado Livre" aria-label="Cotação">
          <ha-icone nome="busca" [tamanho]="15" /><span>Cotação</span>
        </a>
        <a routerLink="/lojas" routerLinkActive="ativo" title="Lojas" aria-label="Lojas">
          <ha-icone nome="loja" [tamanho]="15" /><span>Lojas</span>
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
  protected readonly marcas = Object.values(MARCAS);
}
