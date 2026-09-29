@echo off
setlocal
chcp 65001 >nul
title HelpAgent HOMOLOGACAO - inicializador

rem ===========================================================================
rem  Sobe o HELP-AGENT de HOMOLOGACAO (testes antes de ir para a producao), so nesta maquina:
rem   - backend  (Spring Boot, perfil local)  -> 127.0.0.1:%PORTA_API%
rem   - frontend (Angular)                    -> http://localhost:%PORTA_WEB%  (so localhost, nao escuta na rede)
rem
rem  Nao encosta na producao (iniciar-helpagent.bat, portas 80/8080):
rem   - portas proprias;
rem   - dados proprios em backend\dados-homologacao\ (banco, PDFs, prints, logs e o perfil do Chrome da
rem     cotacao - dois Chromes nao podem usar o mesmo perfil ao mesmo tempo);
rem   - a tela mostra uma faixa "Ambiente de homologacao".
rem  O ideal e rodar este .bat a partir da pasta da branch homologacao (git worktree), para o codigo em teste
rem  nao se misturar com o da producao. Para parar: parar-homologacao.bat
rem ===========================================================================

set "PORTA_WEB=4201"
set "PORTA_API=8091"

set "RAIZ=%~dp0"
set "DADOS=./dados-homologacao"
rem Mesmo contorno do .bat de producao: TEMP com nome curto 8.3 quebra o socket interno da JVM.
set "JVM_TMP=%USERPROFILE%\.helpagent-tmp"
if not exist "%JVM_TMP%" mkdir "%JVM_TMP%"

where java >nul 2>&1 || (echo [ERRO] Java nao encontrado no PATH. Instale o JDK 21. & pause & exit /b 1)
where node >nul 2>&1 || (echo [ERRO] Node.js nao encontrado no PATH. & pause & exit /b 1)

if not exist "%RAIZ%backend\config\application-local.yml" (
  echo [AVISO] backend\config\application-local.yml nao existe: a extracao com IA vai falhar sem a chave do Gemini.
  echo         Veja o README, secao "Chave do Gemini". A cotacao e o orcamento por cotacao funcionam sem ela.
  echo.
)

netstat -ano | findstr /R /C:":%PORTA_API% .*LISTENING" >nul && (
  echo [AVISO] A porta %PORTA_API% ja esta em uso - a homologacao provavelmente ja esta rodando. Use parar-homologacao.bat antes.
  pause & exit /b 1
)
netstat -ano | findstr /R /C:":%PORTA_WEB% .*LISTENING" >nul && (
  echo [AVISO] A porta %PORTA_WEB% ja esta em uso. Use parar-homologacao.bat ou troque PORTA_WEB neste arquivo.
  pause & exit /b 1
)

if not exist "%RAIZ%frontend\node_modules" (
  echo Instalando dependencias do frontend pela primeira vez...
  rem registry.npmjs.org e bloqueado na rede da empresa; o espelho do Yarn serve os mesmos pacotes.
  pushd "%RAIZ%frontend"
  call npm install --registry=https://registry.yarnpkg.com --no-audit --no-fund || (popd & echo [ERRO] npm install falhou. & pause & exit /b 1)
  popd
)

rem Tudo que o backend grava vai para dados-homologacao (as variaveis passam para a janela aberta pelo start).
set "PORT=%PORTA_API%"
set "HELPAGENT_AMBIENTE=homologacao"
set "SPRING_DATASOURCE_URL=jdbc:h2:file:%DADOS%/helpagent-local;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH"
set "ARMAZENAMENTO_DIR=%DADOS%/arquivos"
set "COTACAO_PRINTS=%DADOS%/prints"
set "COTACAO_PERFIL=%DADOS%/navegador"
set "LOGGING_FILE_NAME=%DADOS%/logs/helpagent.log"

echo Iniciando backend de homologacao (porta %PORTA_API%)...
rem Mesma linha do .bat de producao (caminho completo do mvnw.cmd, aspas externas extras) - nao simplifique.
start "HelpAgent HOMOLOGACAO - backend" /D "%RAIZ%backend" cmd /k ""%RAIZ%backend\mvnw.cmd" -q spring-boot:run -Dspring-boot.run.profiles=local "-Dspring-boot.run.jvmArguments=-Djdk.net.unixdomain.tmpdir=%JVM_TMP%""

echo Aguardando o backend responder (a primeira vez pode levar alguns minutos)...
set /a TENTATIVAS=0
:espera
set /a TENTATIVAS+=1
curl -s -o nul --max-time 2 http://127.0.0.1:%PORTA_API%/actuator/health && goto pronto
if %TENTATIVAS% GEQ 150 (
  echo [ERRO] O backend nao respondeu em 5 minutos. Veja a janela "HelpAgent HOMOLOGACAO - backend".
  pause & exit /b 1
)
timeout /t 2 /nobreak >nul
goto espera

:pronto
echo Backend no ar. Iniciando frontend de homologacao (porta %PORTA_WEB%)...
rem A configuracao "homologacao" (frontend/angular.json) escuta so em 127.0.0.1 e manda /api para a porta %PORTA_API%.
start "HelpAgent HOMOLOGACAO - frontend" /D "%RAIZ%frontend" cmd /k npx ng serve --configuration homologacao

echo.
echo ===========================================================================
echo  HELP-AGENT HOMOLOGACAO no ar (o frontend termina de compilar em alguns segundos):
echo    http://localhost:%PORTA_WEB%   (so nesta maquina)
echo  Dados de teste em backend\dados-homologacao - a producao nao e afetada.
echo  Mantenha as duas janelas abertas. Para parar: parar-homologacao.bat
echo ===========================================================================
timeout /t 8 /nobreak >nul
start "" "http://localhost:%PORTA_WEB%"
endlocal
