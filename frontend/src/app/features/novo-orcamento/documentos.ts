import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { DomSanitizer, SafeResourceUrl } from '@angular/platform-browser';
import { Anexo } from '../../core/modelos';
import { Icone } from '../../layout/icone';

interface Aba {
  rotulo: string;
  anexo: Anexo;
  /** PDF precisa de URL "confiável" para o iframe; imagem usa a URL direta. */
  pdfUrl: SafeResourceUrl | null;
}

/**
 * Os documentos originais ao lado do formulário de revisão (tela dividida), em abas: o atendente confere
 * cada valor olhando a fonte, em vez de conferir de memória. Foi o que faltou no caso da proposta Veeam, em
 * que a IA somou anotações à mão e o valor certo estava impresso na mesma página.
 *
 * Imagem: clique alterna entre "caber na largura" e tamanho real (para ler letra miúda).
 * PDF: visualizador do próprio navegador (rolagem, zoom e páginas).
 */
@Component({
  selector: 'ha-documentos',
  imports: [Icone],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <aside class="documentos" aria-label="Documentos originais">
      <div class="doc-abas" role="tablist">
        @for (a of abas(); track a.anexo.id; let i = $index) {
          <button role="tab" [attr.aria-selected]="i === atual()" [class.ativo]="i === atual()" (click)="selecionar(i)">
            {{ a.rotulo }}
          </button>
        }
      </div>
      @if (abas()[atual()]; as a) {
        <div class="doc-barra">
          <span>{{ a.anexo.tipo === 'PDF' ? 'PDF' : ampliada() ? 'Tamanho real' : 'Ajustada à largura' }}</span>
          <a [href]="a.anexo.previewUrl" target="_blank" rel="noopener">Abrir em nova aba</a>
        </div>
        <div class="doc-area" [class.ampliada]="ampliada()">
          @if (a.pdfUrl) {
            <iframe [src]="a.pdfUrl" [title]="a.rotulo"></iframe>
          } @else {
            <img [src]="a.anexo.previewUrl" [alt]="a.rotulo" (click)="ampliada.set(!ampliada())"
              [title]="ampliada() ? 'Clique para ajustar à largura' : 'Clique para ver em tamanho real'">
          }
        </div>
      } @else {
        <div class="doc-vazio"><ha-icone nome="recibo" [tamanho]="28" /><p>Sem documentos anexados.</p></div>
      }
    </aside>
  `,
})
export class Documentos {
  readonly chamados = input<Anexo[]>([]);
  readonly orcamentos = input<Anexo[]>([]);

  private readonly sanitizer = inject(DomSanitizer);
  protected readonly atual = signal(0);
  protected readonly ampliada = signal(false);

  /** Orçamentos primeiro: é neles que estão os valores a conferir. */
  protected readonly abas = computed<Aba[]>(() => {
    const orcs = this.orcamentos();
    const chs = this.chamados();
    const aba = (anexo: Anexo, rotulo: string): Aba => ({
      rotulo,
      anexo,
      // blob: criado por nós a partir do arquivo do próprio usuário — seguro para o iframe.
      pdfUrl: anexo.tipo === 'PDF' ? this.sanitizer.bypassSecurityTrustResourceUrl(anexo.previewUrl) : null,
    });
    return [
      ...orcs.map((a, i) => aba(a, orcs.length > 1 ? `Orçamento ${i + 1}` : 'Orçamento')),
      ...chs.map((a, i) => aba(a, chs.length > 1 ? `Chamado ${i + 1}` : 'Chamado')),
    ];
  });

  protected selecionar(i: number): void {
    this.atual.set(i);
    this.ampliada.set(false);
  }
}
