import { HttpClient } from '@angular/common/http';
import { DestroyRef, Directive, ElementRef, Injectable, effect, inject, input } from '@angular/core';
import { firstValueFrom } from 'rxjs';

/**
 * Imagens servidas pela API (ex.: prints da cotação) carregadas pelo HttpClient em vez de `<img src="/api/...">`.
 * Motivo: com login (Bearer) ou backend em outro endereço (`apiBase`), o navegador buscando a imagem sozinho não
 * manda o token nem sabe o endereço — a imagem quebraria dentro de um portal. Pelo HttpClient ela passa pelo
 * interceptadorApi como qualquer chamada.
 */
@Injectable({ providedIn: 'root' })
export class ImagensApi {
  private readonly http = inject(HttpClient);

  /** Baixa a imagem e devolve uma URL local (blob:) — quem pede libera com URL.revokeObjectURL. */
  async urlLocal(caminho: string): Promise<string> {
    const blob = await firstValueFrom(this.http.get(caminho, { responseType: 'blob' }));
    return URL.createObjectURL(blob);
  }

  /**
   * Abre a imagem numa aba nova. A aba é aberta já no clique (senão o bloqueador de pop-up barra, porque o
   * download é assíncrono) e recebe a imagem quando ela chega.
   */
  async abrir(caminho: string): Promise<void> {
    const aba = window.open('', '_blank');
    try {
      const url = await this.urlLocal(caminho);
      if (aba) aba.location.href = url;
      else window.open(url, '_blank');
      setTimeout(() => URL.revokeObjectURL(url), 60_000);
    } catch {
      aba?.close();
    }
  }
}

/** `<img [haSrcApi]="'/api/...'">`: como `src`, mas buscando pela API (token, apiBase). */
@Directive({ selector: 'img[haSrcApi]' })
export class SrcApi {
  readonly haSrcApi = input.required<string>();
  private readonly img = inject<ElementRef<HTMLImageElement>>(ElementRef);
  private readonly imagens = inject(ImagensApi);
  private atual: string | null = null;

  constructor() {
    effect(() => {
      const caminho = this.haSrcApi();
      this.imagens.urlLocal(caminho).then(
        (url) => {
          this.liberar();
          this.atual = url;
          this.img.nativeElement.src = url;
        },
        () => this.img.nativeElement.removeAttribute('src'),
      );
    });
    inject(DestroyRef).onDestroy(() => this.liberar());
  }

  private liberar(): void {
    if (this.atual) URL.revokeObjectURL(this.atual);
    this.atual = null;
  }
}
