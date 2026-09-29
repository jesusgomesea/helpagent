/**
 * Configuração lida na hora de abrir (arquivo `config.json`, ao lado do index.html), para o mesmo build servir em
 * qualquer lugar — sozinho, atrás de um gateway ou dentro de um portal maior — sem recompilar (docs/INTEGRACAO.md §6).
 *
 * - `apiBase`: prefixo das chamadas `/api/...`. Vazio (padrão) = mesma origem, com o proxy do `ng serve` ou o do
 *   servidor. Ex.: `"https://gateway.empresa/helpagent"` → `https://gateway.empresa/helpagent/api/lojas`.
 * - `enviarCookies`: manda os cookies da sessão junto (SSO do portal feito por cookie no gateway).
 *
 * Token de login: a página que embute o sistema define, antes de carregá-lo,
 * `window.helpAgent = { token: () => 'eyJ...' }` (pode devolver Promise). Com ele, toda chamada vai com
 * `Authorization: Bearer ...` — é o que o backend exige com a segurança ligada.
 */
export interface ConfiguracaoApp {
  apiBase: string;
  enviarCookies: boolean;
}

/** Gancho que a aplicação hospedeira preenche (ver comentário acima). */
export interface GanchoHospedeiro {
  token?: () => string | null | undefined | Promise<string | null | undefined>;
}

declare global {
  interface Window {
    helpAgent?: GanchoHospedeiro;
  }
}

const PADRAO: ConfiguracaoApp = { apiBase: '', enviarCookies: false };

let atual: ConfiguracaoApp = PADRAO;

/** Configuração em uso (a padrão até {@link carregarConfiguracao} terminar). */
export function configuracao(): ConfiguracaoApp {
  return atual;
}

/**
 * Lê o `config.json` relativo ao `<base href>`. Sem arquivo ou com erro, segue com a padrão — o sistema continua
 * abrindo como sempre abriu. Roda antes da primeira tela (provideAppInitializer em app.config.ts).
 */
export async function carregarConfiguracao(): Promise<void> {
  try {
    const r = await fetch(new URL('config.json', document.baseURI), { cache: 'no-store' });
    if (!r.ok) return;
    const lida = (await r.json()) as Partial<ConfiguracaoApp>;
    atual = {
      apiBase: typeof lida.apiBase === 'string' ? lida.apiBase.replace(/\/+$/, '') : PADRAO.apiBase,
      enviarCookies: lida.enviarCookies === true,
    };
  } catch {
    atual = PADRAO;
  }
}

/** Monta a URL real de um caminho `/api/...` (usado também por quem não passa pelo HttpClient). */
export function urlApi(caminho: string): string {
  return caminho.startsWith('/api/') || caminho === '/api' ? atual.apiBase + caminho : caminho;
}
