#!/bin/sh
set -eu

CERT_DIR=/etc/nginx/certs
CA_DIR=/etc/nginx/ca
HOST="${TLS_HOSTNAME:-localhost}"

valid() {
  [ -s "$CERT_DIR/tls.crt" ] && [ -s "$CERT_DIR/tls.key" ] \
    && openssl x509 -in "$CERT_DIR/tls.crt" -noout -ext subjectAltName 2>/dev/null | grep -q "DNS:gateway"
}

if ! valid; then
  echo "filegrid: generez certificat TLS self-signed pentru $HOST"
  mkdir -p "$CERT_DIR"
  openssl req -x509 -nodes -newkey rsa:2048 -days 825 \
    -subj "/CN=$HOST" \
    -addext "subjectAltName=DNS:$HOST,DNS:localhost,DNS:gateway,IP:127.0.0.1" \
    -keyout "$CERT_DIR/tls.key" -out "$CERT_DIR/tls.crt" 2>/dev/null
  chmod 600 "$CERT_DIR/tls.key"
fi

mkdir -p "$CA_DIR"
cp "$CERT_DIR/tls.crt" "$CA_DIR/ca.crt"
chmod 644 "$CA_DIR/ca.crt"
