@echo off
setlocal
chcp 65001 >nul
title HelpAgent - inicializador

rem ===========================================================================
rem  Sobe o HELP-AGENT Orcamentos para uso do helpdesk na rede.
rem   - backend  (Spring Boot, perfil local)  -> so em 127.0.0.1:8080
rem   - frontend (Angular)                    -> 0.0.0.0:%PORTA_WEB%, com proxy /api para o backend
rem  Cada servidor abre na sua propria janela. Para parar: parar-helpagent.bat
rem ===========================================================================

rem Porta publicada na rede. 80 permite acessar sem ":porta" no endereco.
set "PORTA_WEB=80"
rem Nome que o helpdesk digita no navegador (precisa de registro no DNS interno apontando para esta maquina).
set "ENDERECO=helpagent.rdamasio.com.br"

set "RAIZ=%~dp0"
rem O TEMP desta maquina tem nome curto 8.3 (ELUAN~1.JES), que quebra o socket interno da JVM
rem ("Unable to establish loopback connection"). A JVM passa a usar esta pasta no lugar.
set "JVM_TMP=%USERPROFILE%\.helpagent-tmp"
if not exist "%JVM_TMP%" mkdir "%JVM_TMP%"

where java >nul 2>&1 || (echo [ERRO] Java nao encontrado no PATH. Instale o JDK 21. & pause & exit /b 1)
where node >nul 2>&1 || (echo [ERRO] Node.js nao encontrado no PATH. & pause & exit /b 1)

if not exist "%RAIZ%backend\config\application-local.yml" (
  echo [AVISO] backend\config\application-local.yml nao existe: a extracao com IA vai falhar sem a chave do Gemini.
  echo         Veja o README, secao "Chave do Gemini".
  echo.
)

netstat -ano | findstr /R /C:":8080 .*LISTENING" >nul && (
  echo [AVISO] A porta 8080 ja esta em uso - o backend provavelmente ja esta rodando. Use parar-helpagent.bat antes.
  pause & exit /b 1
)
netstat -ano | findstr /R /C:":%PORTA_WEB% .*LISTENING" >nul && (
  echo [AVISO] A porta %PORTA_WEB% ja esta em uso. Use parar-helpagent.bat ou troque PORTA_WEB neste arquivo.
  pause & exit /b 1
)

if not exist "%RAIZ%frontend\node_modules" (
  echo Instalando dependencias do frontend pela primeira vez...
  rem registry.npmjs.org e bloqueado nesta rede; o espelho do Yarn serve os mesmos pacotes.
  pushd "%RAIZ%frontend"
  call npm install --registry=https://registry.yarnpkg.com --no-audit --no-fund || (popd & echo [ERRO] npm install falhou. & pause & exit /b 1)
  popd
)

echo Iniciando backend...
rem Caminho completo do mvnw.cmd: com NoDefaultCurrentDirectoryInExePath ligado (alguns ambientes ligam),
rem o cmd nao procura comandos na pasta atual e "mvnw.cmd" sozinho nao e encontrado.
rem Aspas externas extras: com mais de duas aspas na linha o cmd /k remove a primeira e a ultima.
start "HelpAgent - backend" /D "%RAIZ%backend" cmd /k ""%RAIZ%backend\mvnw.cmd" -q spring-boot:run -Dspring-boot.run.profiles=local "-Dspring-boot.run.jvmArguments=-Djdk.net.unixdomain.tmpdir=%JVM_TMP%""

echo Aguardando o backend responder (a primeira vez pode levar alguns minutos)...
set /a TENTATIVAS=0
:espera
set /a TENTATIVAS+=1
curl -s -o nul --max-time 2 http://127.0.0.1:8080/actuator/health && goto pronto
if %TENTATIVAS% GEQ 150 (
  echo [ERRO] O backend nao respondeu em 5 minutos. Veja a janela "HelpAgent - backend".
  pause & exit /b 1
)
timeout /t 2 /nobreak >nul
goto espera

:pronto
echo Backend no ar. Iniciando frontend na porta %PORTA_WEB%...
start "HelpAgent - frontend" /D "%RAIZ%frontend" cmd /k npx ng serve --host 0.0.0.0 --port %PORTA_WEB%

echo.
echo ===========================================================================
echo  HELP-AGENT no ar (o frontend termina de compilar em alguns segundos):
echo    Nesta maquina : http://localhost:%PORTA_WEB%
echo    Pela rede     : http://%ENDERECO%
for /f "tokens=2 delims=:" %%i in ('ipconfig ^| findstr /C:"IPv4"') do for /f "tokens=*" %%j in ("%%i") do echo    Pelo IP       : http://%%j:%PORTA_WEB%
echo  Mantenha as duas janelas abertas. Para parar: parar-helpagent.bat
echo ===========================================================================
timeout /t 8 /nobreak >nul
start "" "http://localhost:%PORTA_WEB%"
endlocal
