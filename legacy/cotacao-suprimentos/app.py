# -*- coding: utf-8 -*-
"""Servidor local da cotacao. Sem framework: so a stdlib.

    python app.py            -> http://localhost:8760
    python app.py 9000       -> outra porta
"""
import datetime
import json
import mimetypes
import pathlib
import re
import sys
import threading
import webbrowser
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer

import coletor
import motor
import planilha

RAIZ = pathlib.Path(__file__).parent
STATIC = RAIZ / "static"
PORTA = int(sys.argv[1]) if len(sys.argv) > 1 else 8760

_ultimo = {}          # termo -> resultado, para o download da planilha
_lock_cache = threading.Lock()


class Handler(SimpleHTTPRequestHandler):
    def log_message(self, fmt, *args):
        print("  %s  %s" % (datetime.datetime.now().strftime("%H:%M:%S"), fmt % args))

    # ---------------------------------------------------------------- helpers
    def _envia(self, corpo, status=200, ctype="application/json; charset=utf-8", extra=None):
        if isinstance(corpo, str):
            corpo = corpo.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(corpo)))
        self.send_header("Cache-Control", "no-store")
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(corpo)

    def _corpo_json(self):
        n = int(self.headers.get("Content-Length") or 0)
        return json.loads(self.rfile.read(n).decode("utf-8")) if n else {}

    # ------------------------------------------------------------------- GET
    def do_GET(self):
        caminho = self.path.split("?")[0]
        if caminho == "/":
            caminho = "/index.html"
        arq = STATIC / caminho.lstrip("/")
        if arq.is_file() and STATIC in arq.resolve().parents:
            ctype = mimetypes.guess_type(str(arq))[0] or "application/octet-stream"
            if ctype.startswith("text/") or ctype.endswith(("javascript", "json")):
                ctype += "; charset=utf-8"
            self._envia(arq.read_bytes(), ctype=ctype)
        else:
            self._envia(json.dumps({"erro": "nao encontrado"}), 404)

    # ------------------------------------------------------------------ POST
    def do_POST(self):
        try:
            if self.path == "/api/cotar":
                self._cotar()
            elif self.path == "/api/planilha":
                self._planilha()
            else:
                self._envia(json.dumps({"erro": "rota desconhecida"}), 404)
        except Exception as e:
            import traceback
            traceback.print_exc()
            self._envia(json.dumps({"erro": "%s: %s" % (type(e).__name__, e)}), 500)

    def _cotar(self):
        req = self._corpo_json()
        termo = (req.get("termo") or "").strip()
        if not termo:
            return self._envia(json.dumps({"erro": "informe o produto"}), 400)

        criterios = req.get("criterios") or {}
        paginas = max(1, min(3, int(req.get("paginas") or 1)))

        t0 = datetime.datetime.now()
        anuncios, avisos = coletor.coletar(termo, paginas=paginas)
        if not anuncios:
            return self._envia(json.dumps({
                "erro": "nenhum anuncio coletado para '%s'." % termo,
                "detalhe": "; ".join(avisos) or "a busca nao devolveu resultados"}), 502)

        r = motor.avaliar(anuncios, criterios)
        r["termo"] = termo
        r["avisos"] = avisos
        r["segundos"] = round((datetime.datetime.now() - t0).total_seconds(), 1)
        r["coletado_em"] = t0.strftime("%d/%m/%Y as %H:%M")
        with _lock_cache:
            _ultimo[termo.lower()] = r
        self._envia(json.dumps(r, ensure_ascii=False))

    def _planilha(self):
        req = self._corpo_json()
        termo = (req.get("termo") or "").strip()
        with _lock_cache:
            r = _ultimo.get(termo.lower())
        if not r:
            return self._envia(json.dumps({"erro": "faca a cotacao antes de exportar"}), 400)
        dados = planilha.gerar(termo, r)
        nome = "Cotacao_%s_%s.xlsx" % (
            re.sub(r"[^A-Za-z0-9]+", "_", termo).strip("_")[:40],
            datetime.date.today().strftime("%Y%m%d"))
        self._envia(dados, ctype="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    extra={"Content-Disposition": 'attachment; filename="%s"' % nome})


class Servidor(ThreadingHTTPServer):
    # No Windows, SO_REUSEADDR (o padrao do HTTPServer) deixa um segundo processo
    # bindar a MESMA porta em vez de falhar: os dois ficam de pe e cada requisicao
    # cai num deles. Na pratica isso faz parecer que o codigo novo nao subiu, porque
    # metade das chamadas e atendida pelo processo antigo. Melhor falhar alto.
    allow_reuse_address = False


def main():
    try:
        srv = Servidor(("127.0.0.1", PORTA), Handler)
    except OSError:
        print("\n  A porta %d ja esta em uso." % PORTA)
        print("  Encerre o servidor anterior ou use outra porta: python app.py %d\n"
              % (PORTA + 1))
        raise SystemExit(1)
    url = "http://localhost:%d" % PORTA
    print("\n  Cotacao R Damasio")
    print("  " + "-" * 46)
    print("  no ar em %s" % url)
    print("  primeira busca demora mais: o Chrome sobe uma vez")
    print("  Ctrl+C para encerrar\n")
    threading.Timer(1.0, lambda: webbrowser.open(url)).start()
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        print("\n  encerrado")
        srv.shutdown()


if __name__ == "__main__":
    main()
