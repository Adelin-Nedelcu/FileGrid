#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")"

rand() { LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c "$1"; }

if [ ! -f .env ]; then
  echo "Generez .env cu secrete aleatoare..."
  key=$(head -c 32 /dev/urandom | base64 | tr -d '\n')
  sed -e "s|^CATALOG_DB_PASSWORD=\$|CATALOG_DB_PASSWORD=$(rand 32)|" \
      -e "s|^STORAGE_DB_PASSWORD=\$|STORAGE_DB_PASSWORD=$(rand 32)|" \
      -e "s|^FTP_PASSWORD=\$|FTP_PASSWORD=$(rand 32)|" \
      -e "s|^S3_ACCESS_KEY=\$|S3_ACCESS_KEY=$(rand 20)|" \
      -e "s|^S3_SECRET_KEY=\$|S3_SECRET_KEY=$(rand 40)|" \
      -e "s|^FILEGRID_ENCRYPTION_KEY=\$|FILEGRID_ENCRYPTION_KEY=$key|" \
      .env.example > .env
  chmod 600 .env
fi

docker compose up --build -d "$@"

port=$(grep -E '^HTTPS_PORT=' .env | cut -d= -f2)
echo ""
echo "FileGrid: https://localhost:${port:-8443}"
echo "(certificat self-signed - accepta avertismentul browserului)"
