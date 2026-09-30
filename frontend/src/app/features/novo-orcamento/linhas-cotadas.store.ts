import { HttpErrorResponse } from '@angular/common/http';
import { Injectable, OnDestroy, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Avaliado, CotacaoApi } from '../cotacao/cotacao.api';
import { OpcaoCesta, opcaoDe } from '../cotacao/cesta.store';

/** O que vira a linha do impresso quando se escolhe um anúncio no painel "Adicionar por cotação". */
export interface LinhaCotada {
  /** Id do print da escolhida: identifica a linha aqui e no formulário da revisão. */
  chave: string;
  fonte: string;
  titulo: string;
  preco: number;
  url: string;
}

/** Enquanto algum print captura, pergunta ao servidor neste intervalo (o mesmo da cesta da Cotação). */
const INTERVALO_MS = 2500;

/**
 * Orçamento misto (30/09/2026): os prints das linhas "adicionadas por cotação" na revisão do novo orçamento.
 * Cada linha cotada tem a escolhida + até 2 alternativas; o servidor fotografa as páginas em segundo plano
 * (~15 s por item) e este store acompanha até ficarem prontas — o PDF só sai com todos os prints (o servidor confere).
 *
 * Diferente da cesta da tela Cotação ({@link CestaCotacao}), vive só enquanto a página do novo orçamento vive
 * (provido nela) e não vai para o navegador: a revisão inteira também se perde ao recarregar.
 */
@Injectable()
export class LinhasCotadas implements OnDestroy {
  private readonly api = inject(CotacaoApi);

  /** chave da linha → opções (a escolhida primeiro). */
  readonly opcoes = signal<Record<string, OpcaoCesta[]>>({});
  private readonly todas = computed(() => Object.values(this.opcoes()).flat());
  readonly capturando = computed(() => this.todas().filter((o) => o.situacao === 'CAPTURANDO').length);
  readonly falhas = computed(() => this.todas().filter((o) => o.situacao === 'FALHOU').length);
  /** Prints que saíram mas a conferência não achou o preço visível: não impedem gerar, pedem um olhar. */
  readonly alertas = computed(() => this.todas().filter((o) => o.situacao === 'PRONTO' && o.alerta).length);
  /** URLs já escolhidas — o resultado da busca marca "no orçamento". */
  readonly urls = computed(() => new Set(Object.values(this.opcoes()).map((l) => l[0]?.url)));

  private timer?: ReturnType<typeof setTimeout>;

  /** Pede os prints da escolhida e das alternativas e devolve os dados da linha nova. */
  async adicionar(cotacaoId: string, escolhida: Avaliado, alternativas: Avaliado[]): Promise<LinhaCotada> {
    const anuncios = [escolhida, ...alternativas].map((a) => a.anuncio);
    const prints = await firstValueFrom(this.api.solicitarPrints(cotacaoId, anuncios.map((a) => a.url)));
    const chave = prints[0].id;
    this.opcoes.update((m) => ({ ...m, [chave]: prints.map(opcaoDe) }));
    this.acompanhar();
    const a = escolhida.anuncio;
    return { chave, fonte: a.fonte, titulo: a.titulo, preco: a.preco, url: a.url };
  }

  /** Ids dos prints da linha, a escolhida primeiro — vão no pedido de gerar. */
  prints(chave: string): string[] {
    return (this.opcoes()[chave] ?? []).map((o) => o.printId);
  }

  remover(chave: string): void {
    this.opcoes.update((m) => {
      const { [chave]: _, ...resto } = m;
      return resto;
    });
  }

  limpar(): void {
    clearTimeout(this.timer);
    this.opcoes.set({});
  }

  async recapturar(printId: string): Promise<void> {
    this.trocar(opcaoDe(await firstValueFrom(this.api.recapturar(printId))));
    this.acompanhar();
  }

  async anexarManual(printId: string, arquivo: Blob, nome: string): Promise<void> {
    this.trocar(opcaoDe(await firstValueFrom(this.api.anexarPrint(printId, arquivo, nome))));
  }

  urlImagem(o: OpcaoCesta): string {
    return this.api.urlImagem(o.printId, o.capturadoEm);
  }

  ngOnDestroy(): void {
    clearTimeout(this.timer);
  }

  /** Enquanto houver print capturando, consulta o servidor; para sozinho quando não houver mais. */
  private acompanhar(): void {
    clearTimeout(this.timer);
    const pendentes = this.todas().filter((o) => o.situacao === 'CAPTURANDO').map((o) => o.printId);
    if (!pendentes.length) return;
    this.timer = setTimeout(async () => {
      try {
        const atuais = await firstValueFrom(this.api.situacaoPrints(pendentes));
        atuais.forEach((p) => this.trocar(opcaoDe(p)));
      } catch (e) {
        // 404: o servidor não conhece mais o print — vira falha para o atendente tirar de novo ou remover a linha
        if (e instanceof HttpErrorResponse && e.status === 404) {
          pendentes.forEach((id) => this.trocar({ ...this.achar(id)!, situacao: 'FALHOU', motivo: 'o servidor não tem mais este print' }));
        }
      }
      this.acompanhar();
    }, INTERVALO_MS);
  }

  private achar(printId: string): OpcaoCesta | undefined {
    return this.todas().find((o) => o.printId === printId);
  }

  private trocar(nova: OpcaoCesta): void {
    this.opcoes.update((m) =>
      Object.fromEntries(Object.entries(m).map(([k, l]) => [k, l.map((o) => (o.printId === nova.printId ? nova : o))])),
    );
  }
}
