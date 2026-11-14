# syntax=docker/dockerfile:1

FROM node:24-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci --no-audit --no-fund
COPY . .
RUN npx ng build --configuration production

FROM nginx:1.28-alpine
RUN apk add --no-cache openssl
COPY --from=build /app/dist/filegrid-ui/browser /usr/share/nginx/html
COPY nginx/default.conf /etc/nginx/conf.d/default.conf
COPY --chmod=755 nginx/15-filegrid-tls.sh /docker-entrypoint.d/15-filegrid-tls.sh
RUN sed -i 's/\r$//' /docker-entrypoint.d/15-filegrid-tls.sh
EXPOSE 443
