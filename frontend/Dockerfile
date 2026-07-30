FROM node:22-alpine AS build
WORKDIR /build

COPY package.json package-lock.json ./
RUN npm ci

COPY . .
RUN npm run build

FROM nginx:alpine
COPY --from=build /build/dist /usr/share/nginx/html
# La SPA no tiene rutas de servidor, pero el fallback a index.html evita un 404
# si algún día se navega directo a una ruta interna.
COPY nginx-spa.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
