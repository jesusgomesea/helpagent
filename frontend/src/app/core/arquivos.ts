import { Anexo } from './modelos';

let seq = 0;

/**
 * Maior lado de uma imagem depois de otimizada. Prints de monitor 2K/4K têm muito mais pixels do que a IA
 * precisa para ler texto; mandar menos acelera o envio e a leitura (a IA processa e cobra por pixel).
 * 2000 px ainda dá ~240 dpi numa folha A4 no PDF anexado.
 */
const LADO_MAXIMO = 2000;
/** Acima disso, mesmo sem reduzir a resolução, recomprime como JPEG (PNG de foto fica enorme). */
const BYTES_MAXIMO = 1_500_000;

/**
 * Prepara um arquivo para virar anexo. Imagens são otimizadas antes de sair do navegador
 * (ver LADO_MAXIMO); formatos que não são PNG/JPEG (webp, gif…) viram PNG — o PDF final
 * só embute bem esses dois.
 */
export async function criarAnexo(arquivo: Blob, nome: string): Promise<Anexo> {
  const ehPdf = arquivo.type === 'application/pdf' || /\.pdf$/i.test(nome);
  if (ehPdf) {
    return { id: `a${++seq}`, arquivo, nome, previewUrl: URL.createObjectURL(arquivo), tipo: 'PDF' };
  }
  if (!arquivo.type.startsWith('image/')) {
    throw new Error('Envie uma imagem ou um PDF.');
  }
  const final = await otimizarImagem(arquivo);
  return { id: `a${++seq}`, arquivo: final, nome, previewUrl: URL.createObjectURL(final), tipo: 'imagem' };
}

export function liberarAnexo(a: Anexo): void {
  URL.revokeObjectURL(a.previewUrl);
}

/** Reduz para LADO_MAXIMO e recomprime o que for pesado. Imagem já pequena passa intacta. */
async function otimizarImagem(arquivo: Blob): Promise<Blob> {
  const formatoOk = arquivo.type === 'image/png' || arquivo.type === 'image/jpeg';
  const bitmap = await createImageBitmap(arquivo);
  const escala = Math.min(1, LADO_MAXIMO / Math.max(bitmap.width, bitmap.height));
  if (formatoOk && escala === 1 && arquivo.size <= BYTES_MAXIMO) {
    bitmap.close();
    return arquivo;
  }
  const canvas = document.createElement('canvas');
  canvas.width = Math.round(bitmap.width * escala);
  canvas.height = Math.round(bitmap.height * escala);
  const ctx = canvas.getContext('2d')!;
  ctx.fillStyle = '#ffffff'; // PNG transparente não pode virar fundo preto no JPEG
  ctx.fillRect(0, 0, canvas.width, canvas.height);
  ctx.imageSmoothingQuality = 'high';
  ctx.drawImage(bitmap, 0, 0, canvas.width, canvas.height);
  bitmap.close();
  // Print de tela continua PNG (texto mais nítido) se couber; foto ou PNG pesado vai de JPEG.
  if (arquivo.type !== 'image/jpeg') {
    const png = await canvasParaBlob(canvas, 'image/png');
    if (png.size <= BYTES_MAXIMO) return png;
  }
  return canvasParaBlob(canvas, 'image/jpeg', 0.88);
}

/**
 * Texto digitado/colado vira imagem PNG — mantém um único pipeline (IA lê imagem, PDF anexa imagem).
 * Porta de textoParaImagemDataURL da v3.5.
 */
export async function textoParaImagem(texto: string): Promise<Blob> {
  const largura = 1000, margem = 48, fonte = 26, lh = 38;
  const maxW = largura - margem * 2;
  const canvas = document.createElement('canvas');
  const ctx = canvas.getContext('2d')!;
  ctx.font = `${fonte}px Inter, Arial, sans-serif`;
  const linhas: string[] = [];
  for (const par of texto.split(/\r?\n/)) {
    if (par === '') {
      linhas.push('');
      continue;
    }
    let atual = '';
    for (const pal of par.split(/\s+/)) {
      const teste = atual ? `${atual} ${pal}` : pal;
      if (ctx.measureText(teste).width > maxW && atual) {
        linhas.push(atual);
        atual = pal;
      } else {
        atual = teste;
      }
    }
    if (atual) linhas.push(atual);
  }
  canvas.width = largura;
  canvas.height = Math.max(200, margem * 2 + linhas.length * lh + 40);
  ctx.fillStyle = '#ffffff';
  ctx.fillRect(0, 0, canvas.width, canvas.height);
  ctx.fillStyle = '#1a73e8';
  ctx.fillRect(0, 0, canvas.width, 6);
  ctx.fillStyle = '#202124';
  ctx.font = `${fonte}px Inter, Arial, sans-serif`;
  ctx.textBaseline = 'top';
  let y = margem + 20;
  for (const l of linhas) {
    ctx.fillText(l, margem, y);
    y += lh;
  }
  return canvasParaBlob(canvas);
}

function canvasParaBlob(canvas: HTMLCanvasElement, tipo = 'image/png', qualidade?: number): Promise<Blob> {
  return new Promise((ok, erro) =>
    canvas.toBlob((b) => (b ? ok(b) : erro(new Error('Falha ao gerar imagem'))), tipo, qualidade),
  );
}
