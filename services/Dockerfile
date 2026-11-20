# Una imagen por servicio: docker build --build-arg SERVICIO=mercado services/
FROM golang:1.27-alpine AS build
ARG SERVICIO
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN go test ./internal/... && CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /servicio ./cmd/${SERVICIO}

FROM alpine:3.22
RUN apk add --no-cache ca-certificates tzdata && adduser -D -u 10001 app && mkdir -p /datos && chown app /datos
COPY --from=build /servicio /usr/local/bin/servicio
USER app
ENV DATOS=/datos
CMD ["servicio"]
