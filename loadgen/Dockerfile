FROM golang:1.27-bookworm AS build
WORKDIR /src
COPY loadgen/go.mod ./
COPY loadgen/main.go ./
RUN go build -o /loadgen .

FROM debian:bookworm-slim
COPY --from=build /loadgen /loadgen
ENTRYPOINT ["/loadgen"]
