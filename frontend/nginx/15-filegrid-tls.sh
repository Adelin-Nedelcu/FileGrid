#!/bin/sh
set -eu

CERT_DIR=/etc/nginx/certs
HOST="${TLS_HOSTNAME:-localhost}"

if [ ! -s "$CERT_DIR/tls.crt" ] || [ ! -s "$CERT_DIR/tls.key" ]; then
  echo "filegrid: generez certificat TLS self-signed pentru $HOST"
  mkdir -p "$CERT_DIR"
  openssl req -x509 -nodes -newkey rsa:2048 -days 825 \
    -subj "/CN=$HOST" \
    -addext "subjectAltName=DNS:$HOST,DNS:localhost,IP:127.0.0.1" \
    -keyout "$CERT_DIR/tls.key" -out "$CERT_DIR/tls.crt" 2>/dev/null
  chmod 600 "$CERT_DIR/tls.key"
fi
