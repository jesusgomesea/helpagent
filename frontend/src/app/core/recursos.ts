import { Injectable, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { CanMatchFn, Router } from '@angular/router';
import { catchError, map, of } from 'rxjs';
import { Api } from './api';
import { RecursosLigados } from './modelos';

/**
 * Quais recursos este servidor oferece (helpagent.recursos no backend, lidos de /api/parametros). Numa integração
 * com uma aplicação maior a cotação costuma ficar desligada (precisa de Chrome com janela) e a IA pode ficar
 * (sem chave/internet): o menu e as rotas somem em vez de a tela abrir e dar erro. Enquanto a resposta não chega,
 * tudo conta como ligado — o servidor barra de qualquer jeito (503) o que estiver desligado.
 */
@Injectable({ providedIn: 'root' })
export class Recursos {
  private readonly lidos = toSignal(
    inject(Api)
      .parametros()
      .pipe(
        map((p) => p.recursos),
        catchError(() => of(null)),
      ),
    { initialValue: null },
  );

  readonly ia = computed(() => this.lidos()?.ia ?? true);
  readonly cotacao = computed(() => this.lidos()?.cotacao ?? true);
}

/** Rota só existe com o recurso ligado; desligado, volta para o início. */
export function recursoLigado(recurso: keyof RecursosLigados): CanMatchFn {
  return () => {
    const router = inject(Router);
    return inject(Api)
      .parametros()
      .pipe(
        map((p) => (p.recursos?.[recurso] ?? true) || router.parseUrl('/')),
        catchError(() => of(true)),
      );
  };
}
