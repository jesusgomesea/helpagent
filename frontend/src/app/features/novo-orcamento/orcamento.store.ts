import { Injectable, computed, signal } from '@angular/core';
import { liberarAnexo } from '../../core/arquivos';
import { Anexo, ModoAquisicao, RespostaExtracao, exigeChamado } from '../../core/modelos';

export type Destino = 'chamado' | 'orc';

/**
 * Estado do orçamento em montagem. Substitui as variáveis globais da v3.5
 * (chamados, orcamentos, modoAquisicao, dadosComuns…). Vive enquanto a página vive.
 */
@Injectable()
export class OrcamentoStore {
  /** Requisição/Chamado é o padrão: é o fluxo normal (o que a tela chamava de OPEX até 28/09/2026). */
  readonly modo = signal<ModoAquisicao>('REQUISICAO');
  readonly exigeChamado = computed(() => exigeChamado(this.modo()));
  readonly chamados = signal<Anexo[]>([]);
  readonly orcamentos = signal<Anexo[]>([]);
  readonly destino = signal<Destino>('chamado');
  /** 1 imagens · 2 extração · 3 revisão · 4 PDF */
  readonly etapa = signal(1);
  readonly extracao = signal<RespostaExtracao | null>(null);
  readonly pdfGerado = signal(false);

  readonly prontoParaExtrair = computed(() =>
    !this.exigeChamado()
      ? this.orcamentos().length > 0
      : this.chamados().length > 0 && this.orcamentos().length > 0,
  );

  /** Em CAPEX o chamado é opcional e não vai para a IA — mas vai anexado ao PDF se existir. */
  readonly chamadosParaIa = computed(() => (this.exigeChamado() ? this.chamados() : []));

  setModo(m: ModoAquisicao): void {
    this.modo.set(m);
    this.destino.set(!exigeChamado(m) || this.chamados().length > 0 ? 'orc' : 'chamado');
  }

  adicionar(a: Anexo): string {
    if (this.destino() === 'chamado') {
      this.chamados.update((l) => [...l, a]);
      return `Chamado ${this.chamados().length} adicionado`;
    }
    this.orcamentos.update((l) => [...l, a]);
    return `Orçamento ${this.orcamentos().length} adicionado`;
  }

  remover(papel: Destino, id: string): void {
    const lista = papel === 'chamado' ? this.chamados : this.orcamentos;
    lista.update((l) => {
      l.filter((a) => a.id === id).forEach(liberarAnexo);
      return l.filter((a) => a.id !== id);
    });
    if (papel === 'chamado' && this.chamados().length === 0 && this.exigeChamado()) this.destino.set('chamado');
  }

  reiniciar(): void {
    [...this.chamados(), ...this.orcamentos()].forEach(liberarAnexo);
    this.chamados.set([]);
    this.orcamentos.set([]);
    this.extracao.set(null);
    this.pdfGerado.set(false);
    this.etapa.set(1);
    this.setModo(this.modo());
  }
}
