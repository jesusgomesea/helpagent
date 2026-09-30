import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { CurrencyPipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { mensagensDeErro } from '../../core/api';
import { Avisos } from '../../core/avisos';
import { Faixa } from '../../layout/faixa';
import { BuscaCotacao, EscolhaCotacao } from './busca-cotacao';
import { CestaCotacao, MAX_ITENS_CESTA, alternativasPara } from './cesta.store';

/**
 * Cotação em lojas online (funcionalidade à parte do orçamento, ampliada do piloto de Suprimentos). A busca em si
 * (termo, onde buscar, critérios, resultado) é o {@link BuscaCotacao}, o mesmo do painel "Adicionar por cotação"
 * da revisão do novo orçamento. Nada é gravado: o resultado vale 30 min para baixar a planilha.
 *
 * Orçamento por cotação: "escolher para o orçamento" põe o anúncio na cesta ({@link CestaCotacao}) com mais 2
 * opções para comparar, e o servidor fotografa as 3 páginas. A barra no rodapé leva à tela de revisão e geração.
 */
@Component({
  selector: 'ha-cotacao',
  imports: [Faixa, BuscaCotacao, RouterLink, CurrencyPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <ha-faixa sobretitulo="Compras · Análise de mercado" titulo="Cotação"
      subtitulo="Digite o item e escolha onde buscar. O sistema varre as lojas, corta quem não atende aos requisitos mínimos e ordena o que sobrou pelo peso que você deu a preço, prazo, reputação e marca." />
    <main class="container">
      <ha-busca-cotacao [escolhidas]="cesta.urlsEscolhidas()" [cheia]="cesta.itens().length >= maxItens" (escolher)="escolher($event)" />
    </main>

    @if (cesta.itens().length) {
      <aside class="cesta-barra" aria-label="Orçamento por cotação">
        <div class="cesta-info">
          <b>Orçamento por cotação</b>
          <span>{{ cesta.itens().length }}/{{ maxItens }} ite{{ cesta.itens().length > 1 ? 'ns' : 'm' }} · {{ cesta.total() | currency: 'BRL' }}</span>
          @if (cesta.capturando()) { <span class="cesta-capturando">tirando {{ cesta.capturando() }} print{{ cesta.capturando() > 1 ? 's' : '' }}…</span> }
          @if (cesta.falhas()) { <span class="cesta-falha">{{ cesta.falhas() }} print{{ cesta.falhas() > 1 ? 's' : '' }} com falha</span> }
        </div>
        <a class="btn-acao" routerLink="/orcamento-cotacao">Revisar e gerar →</a>
      </aside>
    }
  `,
})
export class CotacaoPage {
  private readonly avisos = inject(Avisos);
  protected readonly cesta = inject(CestaCotacao);
  protected readonly maxItens = MAX_ITENS_CESTA;

  protected async escolher({ resposta: r, avaliado: a }: EscolhaCotacao): Promise<void> {
    const alternativas = alternativasPara(r.resultado, a);
    try {
      await this.cesta.adicionar(r.id, r.termo, a, alternativas);
      this.avisos.toast(`Item ${this.cesta.itens().length} no orçamento · tirando ${alternativas.length + 1} prints`);
    } catch (e) {
      this.avisos.toast(e instanceof Error ? e.message : (await mensagensDeErro(e)).join(' '), '⚠');
    }
  }
}
