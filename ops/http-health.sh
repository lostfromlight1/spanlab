#!/bin/bash
# Compose health check. The images have Bash and no curl.
# Usage: http-health.sh PORT PATH
# Opens a TCP connection, sends one HTTP/1.1 GET, and succeeds only
# when the status line is HTTP/1.1 200.
port="$1"
path="$2"
exec 3<>"/dev/tcp/127.0.0.1/${port}"
printf 'GET %s HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n' "$path" >&3
grep -q 'HTTP/1.1 200' <&3
