# Cotacao Assistida - verificacao de pre-requisitos e instalacao
#
#   Uso:  powershell -ExecutionPolicy Bypass -File instalar.ps1
#
# Nao exige privilegio de administrador: instala com --user.

$ErrorActionPreference = "Stop"
$falhas = 0

function Ok   ($m) { Write-Host "  [OK]    $m" -ForegroundColor Green }
function Aviso($m) { Write-Host "  [AVISO] $m" -ForegroundColor Yellow }
function Erro ($m) { Write-Host "  [ERRO]  $m" -ForegroundColor Red; $script:falhas++ }

Write-Host ""
Write-Host "  Cotacao Assistida - verificacao do ambiente" -ForegroundColor Cyan
Write-Host "  --------------------------------------------"

# 1. Windows
$os = Get-CimInstance Win32_OperatingSystem
Ok "$($os.Caption) (build $($os.BuildNumber))"

# 2. Sessao interativa - requisito critico
if ([Environment]::UserInteractive) {
    Ok "Sessao interativa ativa (usuario $env:USERNAME)"
} else {
    Erro "Sem sessao interativa. O Chrome nao sobe em servico/sessao 0 - ver HANDOFF secao 5.3"
}

# 3. Python
try {
    # sem 2>&1: no PowerShell 5.1 redirecionar stderr de exe nativo gera
    # NativeCommandError mesmo quando o comando teve sucesso
    $pv = (& python -V) -replace 'Python\s*',''
    $maj, $min = $pv.Split('.')[0..1]
    if ([int]$maj -gt 3 -or ([int]$maj -eq 3 -and [int]$min -ge 10)) {
        Ok "Python $pv em $((Get-Command python).Source)"
    } else {
        Erro "Python $pv - a aplicacao pede 3.10 ou superior"
    }
} catch {
    Erro "Python nao encontrado no PATH. Instale o Python 3.10+ (64 bits) e marque 'Add to PATH'"
}

# 4. Google Chrome
$chromes = @(
    "C:\Program Files\Google\Chrome\Application\chrome.exe",
    "C:\Program Files (x86)\Google\Chrome\Application\chrome.exe",
    "$env:LOCALAPPDATA\Google\Chrome\Application\chrome.exe"
)
$chrome = $chromes | Where-Object { Test-Path $_ } | Select-Object -First 1
if ($chrome) {
    Ok "Google Chrome $((Get-Item $chrome).VersionInfo.ProductVersion)"
} else {
    $edge = "C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"
    if (Test-Path $edge) {
        Aviso "Google Chrome nao encontrado, mas ha Edge. Troque channel='chrome' por channel='msedge' em coletor.py - ver HANDOFF secao 5.4"
    } else {
        Erro "Nenhum navegador compativel. Instale o Google Chrome"
    }
}

# 5. Espaco em disco
$drive = (Get-Item $PSScriptRoot).PSDrive
$livreGB = [math]::Round($drive.Free / 1GB, 1)
if ($livreGB -ge 1) { Ok "Espaco livre em $($drive.Name): $livreGB GB" }
else { Erro "So $livreGB GB livres. O perfil do Chrome ocupa ~330 MB" }

# 6. Porta 8760
$porta = Get-NetTCPConnection -LocalPort 8760 -State Listen -ErrorAction SilentlyContinue
if ($porta) {
    Aviso "Porta 8760 ja esta em uso (PID $($porta.OwningProcess)). Encerre o processo ou use: python app.py 8761"
} else {
    Ok "Porta 8760 livre"
}

# 7. Acesso ao Mercado Livre
try {
    $r = Invoke-WebRequest "https://www.mercadolivre.com.br" -UseBasicParsing -TimeoutSec 15 -MaximumRedirection 3
    Ok "Acesso ao Mercado Livre (HTTP $($r.StatusCode))"
} catch {
    Aviso "Nao alcancou mercadolivre.com.br por HTTP direto. Pode ser proxy - confirme abrindo o site no Chrome"
}

if ($falhas -gt 0) {
    Write-Host ""
    Write-Host "  $falhas requisito(s) obrigatorio(s) faltando. Instalacao interrompida." -ForegroundColor Red
    Write-Host ""
    exit 1
}

# 8. Dependencias Python
Write-Host ""
Write-Host "  Instalando dependencias..." -ForegroundColor Cyan
Push-Location $PSScriptRoot
try {
    & python -m pip install --user --disable-pip-version-check -r requirements.txt
    if ($LASTEXITCODE -ne 0) { throw "pip retornou $LASTEXITCODE" }
    Ok "playwright e openpyxl instalados"
} catch {
    Erro "Falha no pip: $_"
    Write-Host ""
    Write-Host "  Se a rede usa proxy:" -ForegroundColor Yellow
    Write-Host "    python -m pip install --user --proxy http://usuario:senha@proxy:porta -r requirements.txt"
    Pop-Location
    exit 1
}

# 9. Teste de fumaca: o Chrome sobe e a coleta funciona?
Write-Host ""
Write-Host "  Testando a coleta (pode levar ~30s na primeira vez)..." -ForegroundColor Cyan
$log = Join-Path $env:TEMP "cotacao_smoke.log"
& python -X utf8 -c "import coletor; a,v = coletor.coletar('ssd 256gb'); print('ANUNCIOS=%d' % len(a))" > $log
$teste = Get-Content $log -Raw
Remove-Item $log -ErrorAction SilentlyContinue
Pop-Location

if ($teste -match 'ANUNCIOS=(\d+)') {
    $n = [int]$Matches[1]
    if ($n -gt 0) { Ok "Coleta funcionando: $n anuncios lidos" }
    else { Erro "A coleta rodou mas nao trouxe anuncios - ver HANDOFF secao 8.5" }
} else {
    Erro "A coleta falhou. Saida:"
    Write-Host $teste
    Write-Host "  Ver HANDOFF secao 8.5 (diagnostico) e 5.6 (antivirus/EDR)" -ForegroundColor Yellow
    exit 1
}

Write-Host ""
Write-Host "  Tudo pronto. Para iniciar:  python app.py    (ou duplo clique em iniciar.bat)" -ForegroundColor Green
Write-Host ""
