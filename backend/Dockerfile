# Imagem do backend (docs/INTEGRACAO.md §7). Dois estágios: compila com o Maven Wrapper do projeto e roda só com o
# JRE, como usuário sem privilégio.
#
# A COTAÇÃO NÃO FUNCIONA AQUI DENTRO: ela dirige um Chrome com janela (o Mercado Livre bloqueia headless) e a
# imagem não tem Chrome nem desktop. Por isso RECURSO_COTACAO=false por padrão; o resto (orçamento, impresso,
# histórico, lojas, IA) funciona normalmente.
#
#   docker build -t helpagent-backend backend/

# Maven oficial em vez do mvnw: o wrapper baixa o Maven com curl/wget, que a imagem pode não ter
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
# o Playwright é dependência de compilação da cotação; o navegador dele nunca é baixado (usamos o Chrome instalado)
ENV PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline
COPY src/ src/
# testes rodam no CI (.github/workflows/ci.yml), não no build da imagem
RUN mvn -B -q -DskipTests package && cp target/helpagent-backend-*.jar /src/app.jar

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 10001 --home /app helpagent && mkdir -p /app/dados && chown -R helpagent /app
COPY --from=build /src/app.jar /app/app.jar
USER helpagent
# PDFs gerados e prints ficam em /app/dados: monte um volume, senão somem ao recriar o contêiner
VOLUME /app/dados
ENV ARMAZENAMENTO_DIR=/app/dados/arquivos \
    COTACAO_PRINTS=/app/dados/prints \
    RECURSO_COTACAO=false \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
