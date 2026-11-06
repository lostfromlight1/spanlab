FROM golang:1.27-bookworm AS build
WORKDIR /src
COPY gateway/go.mod gateway/go.sum ./
RUN go mod download
COPY gateway/ ./
RUN CGO_ENABLED=0 go build -o /out/app .

FROM debian:bookworm-slim
COPY ops/http-health.sh /usr/local/bin/http-health.sh
RUN sed -i 's/\r$//' /usr/local/bin/http-health.sh && chmod +x /usr/local/bin/http-health.sh
COPY --from=build /out/app /usr/local/bin/app
EXPOSE 8080
ENTRYPOINT ["app"]
