# Cotação assistida — R Damásio Suprimentos

> Vai assumir o projeto ou instalar em outra estação? Leia **[HANDOFF.md](HANDOFF.md)**:
> escopo, contratos de dados, requisitos, instalação, pontos frágeis e diagnóstico.
> Para instalar: `powershell -ExecutionPolicy Bypass -File instalar.ps1`

Página local onde o comprador digita o produto, define o critério e recebe o top 3
com a justificativa de cada posição. Fonte atual: **Mercado Livre**.

## Como rodar

```bash
python app.py
```

Abre sozinho em `http://localhost:8760`. Para usar outra porta: `python app.py 9000`.

Se a porta já estiver em uso, o servidor recusa subir e avisa. Isso é proposital: no
Windows, o padrão do `HTTPServer` (`allow_reuse_address`) deixa um segundo processo
bindar a **mesma** porta, e as requisições passam a cair em qualquer um dos dois — dá a
impressão de que a alteração no código não subiu.

A primeira busca é mais lenta (~15s) porque o Chrome precisa subir. As seguintes
levam ~4s por página de resultados.

## Dependências

| Pacote | Para quê | Instalação |
|---|---|---|
| `playwright` | dirigir o Chrome | `pip install playwright` |
| `openpyxl` | gerar a planilha | já vem no ambiente |
| `lxml` | — | já vem no ambiente |

Não é preciso `playwright install`: usamos o Chrome já instalado na máquina
(`channel="chrome"`). Nenhum framework web — o servidor é `http.server` da stdlib.

## Por que um navegador de verdade

Três caminhos foram testados contra o Mercado Livre:

| Abordagem | Resultado |
|---|---|
| `urllib` / `requests` direto | bloqueado — devolve a página de tráfego suspeito |
| API pública `api.mercadolibre.com` | **403** — exige token de aplicação |
| Chrome `--headless` | bloqueado — redireciona para verificação de conta |
| **Chrome real com perfil persistente** | **funciona** |

Por isso o `coletor.py` sobe o Chrome com janela em `--window-position=-3000,-3000`:
fora da área visível, sem atrapalhar quem está usando a máquina. O perfil fica em
`.navegador/` e guarda os cookies que mantêm o acesso liberado.

Esse mesmo padrão deve valer para Kabum e Pichau, que usam proteção equivalente.

## Arquitetura

```
app.py          servidor HTTP e rotas (/api/cotar, /api/planilha)
coletor.py      Chrome + extração dos anúncios      <- adicionar lojas aqui
motor.py        eliminatórios e pontuação
planilha.py     exportação xlsx
static/         a página (index.html, style.css, app.js)
```

### Acrescentar uma loja

Escreva em `coletor.py`:

```python
def buscar_kabum(page, termo, paginas=1):
    ...
    return [{...}]          # mesmas chaves de CAMPOS

FONTES["kabum"] = buscar_kabum
```

O motor, a página e a planilha não mudam — passam a receber anúncios com
`fonte: "Kabum"` e tratam tudo junto.

## Como a decisão é tomada

**1. Eliminatórios** — cortam antes de qualquer pontuação, porque um anúncio sem
reputação não deve competir por preço:

Na ordem em que são testados — o primeiro que bate vira o motivo exibido, por isso
produto vem antes de logística, que vem antes de reputação: assim o motivo reflete o
filtro que a pessoa acabou de aplicar.

1. capacidade/spec errada (via *título deve conter* / *não pode conter*)
2. produto recondicionado (configurável)
3. **origem do envio** — nacional × internacional, quando restringida
4. fora do FULL, se exigido
5. acima do teto de preço
6. vendedor sem avaliação pública
7. nota abaixo do mínimo
8. volume de vendas abaixo do mínimo — **só quando o dado existe**

### Origem do envio

O Mercado Livre marca a oferta do exterior com um texto acessível `Internacional
<país>`. O coletor lê isso e grava `internacional` + `pais`; o filtro tem três
posições: *qualquer origem* (padrão), *só nacional* e *só internacional*.

Importa mais do que parece: numa busca por `modulo esp32`, **13 dos 55 anúncios vinham
da China** — normalmente os mais baratos, com prazo de semanas e possível tributação na
entrada. Sem o filtro, eles disputam o topo do ranking com fornecedor nacional. Quando
há internacional no resultado e o filtro está em *qualquer origem*, a página avisa.

A origem aparece como coluna nas tabelas, como selo no card e na planilha (abas
*Analise* e *Descartados*).

**2. Score ponderado** — só entre os que sobraram. Os quatro pesos são
normalizados, então o que vale é a proporção entre eles:

| Critério | Padrão | Como pontua |
|---|---|---|
| Preço | 40 | menor preço **do segmento** = 100 |
| Entrega | 20 | FULL 100 · frete grátis 60 · comum 20 |
| Fornecedor | 25 | nota + volume + bônus de loja oficial |
| Marca | 15 | marca preferida/reconhecida 100 · demais 35 |

**Segmentação.** O campo *segmentar o ranking por* (ex.: `M.2, SATA`) gera um top 3
por segmento e faz a comparação de preço acontecer dentro do segmento. Sem isso,
uma busca por "ssd 256gb" compara peças fisicamente incompatíveis e o resultado não
serve para comprar.

## Limitações conhecidas

- **Quantidade vendida nem sempre vem.** O Mercado Livre serve variantes de layout
  para a mesma busca; em algumas não há o número de unidades vendidas. Nesse caso o
  sistema **não** assume zero: o filtro de volume não é aplicado, a reputação passa a
  ser calculada só pela nota, e a página avisa quantos anúncios estão nessa condição.
- **Anúncios patrocinados não expõem o link do produto no DOM** — só uma URL de
  rastreamento (`click1.mercadolivre.com.br`), que responde 302 para o anúncio real.
  O coletor resolve esses redirecionamentos em paralelo (~0,3s cada, 8 de cada vez) e
  grava a URL limpa; se algum não resolver, mantém o link de rastreamento, que abre o
  mesmo anúncio. **Nenhum item fica sem link** — nem na lista de elegíveis, nem na de
  descartados, nem na planilha. Patrocinados ficam marcados com `AD`.
- **Preço é do momento da coleta** e varia conforme a conta logada e o nível Meli+.
- A busca cobre 1 a 3 páginas (~50 anúncios cada), conforme a profundidade escolhida.

## Próximos passos

- Kabum, Pichau e Terabyte como fontes adicionais
- histórico de cotações para acompanhar preço no tempo
- decidir o acionamento definitivo (hoje é sob demanda, na página)
