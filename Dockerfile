# Web (React) y API (Go) en una sola imagen pequeña.
FROM node:22-alpine AS web
WORKDIR /web
COPY web/package.json web/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY web/ ./
RUN npm run build

FROM golang:1.27-alpine AS api
WORKDIR /src
COPY backend/go.mod backend/go.sum ./
RUN go mod download
COPY backend/ ./
RUN go test ./... && CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /anaquel .

FROM alpine:3.22
RUN apk add --no-cache ca-certificates tzdata && adduser -D -u 10001 anaquel && mkdir -p /datos && chown anaquel /datos
COPY --from=api /anaquel /usr/local/bin/anaquel
COPY --from=web /web/dist /web
USER anaquel
ENV DATOS=/datos WEB=/web ADDR=:8080
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s CMD wget -qO- http://127.0.0.1:8080/api/salud || exit 1
CMD ["anaquel"]
