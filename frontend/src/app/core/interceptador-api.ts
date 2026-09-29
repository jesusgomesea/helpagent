import { HttpInterceptorFn } from '@angular/common/http';
import { from, switchMap } from 'rxjs';
import { configuracao, urlApi } from './configuracao';

/**
 * Toda chamada `/api/...` passa por aqui (registrado em app.config.ts), para as telas não precisarem saber onde
 * nem como o sistema está hospedado:
 * - prefixa o `apiBase` do config.json (backend em outro endereço/gateway);
 * - manda um `X-Request-Id` novo: o backend usa o mesmo id nos logs e o devolve no erro, que a tela mostra como
 *   "código para o suporte" — liga a reclamação do usuário à linha de log;
 * - com `window.helpAgent.token` definido pela página hospedeira, manda `Authorization: Bearer`;
 * - com `enviarCookies`, manda os cookies (SSO por cookie no gateway).
 */
export const interceptadorApi: HttpInterceptorFn = (req, next) => {
  if (!req.url.startsWith('/api')) return next(req);
  const cfg = configuracao();
  const cabecalhos: Record<string, string> = { 'X-Request-Id': novoId() };
  const obterToken = window.helpAgent?.token;
  return from(Promise.resolve(obterToken ? obterToken() : null)).pipe(
    switchMap((token) => {
      if (token) cabecalhos['Authorization'] = `Bearer ${token}`;
      return next(req.clone({ url: urlApi(req.url), setHeaders: cabecalhos, withCredentials: cfg.enviarCookies }));
    }),
  );
};

function novoId(): string {
  return typeof crypto !== 'undefined' && 'randomUUID' in crypto
    ? crypto.randomUUID()
    : Date.now().toString(36) + Math.random().toString(36).slice(2, 10);
}
