# Imagem do frontend (docs/INTEGRACAO.md §7): compila o Angular e serve com nginx, que também encaminha /api para o
# backend (mesma origem, sem CORS). O config.json pode ser trocado por volume, sem rebuild:
#
#   docker build -t helpagent-frontend frontend/
#   docker build --build-arg BASE_HREF=/helpagent/ -t helpagent-frontend frontend/   # servir num sub-caminho

FROM node:24-alpine AS build
WORKDIR /src
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
ARG BASE_HREF=/
RUN npx ng build --base-href "$BASE_HREF"

FROM nginx:1.29-alpine
# BACKEND_URL: onde o nginx acha a API (no docker-compose, o serviço "backend")
ENV BACKEND_URL=http://backend:8080
COPY nginx.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /src/dist/helpagent/browser/ /usr/share/nginx/html/
EXPOSE 80
