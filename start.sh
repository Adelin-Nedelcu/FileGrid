#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")"

rand() { LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c "$1"; }

secret() {
  case "$1" in
    FILEGRID_ENCRYPTION_KEY|FILEGRID_JWT_SECRET|METER_MASTER_KEY) head -c 32 /dev/urandom | base64 | tr -d '\n' ;;
    S3_ACCESS_KEY) rand 20 ;;
    S3_SECRET_KEY) rand 40 ;;
    PASSWORD_*) rand 14 ;;
    *) rand 32 ;;
  esac
}

touch .env
chmod 600 .env
added=0
while IFS= read -r line || [ -n "$line" ]; do
  case "$line" in
    [A-Z]*=*) ;;
    *) continue ;;
  esac
  name=${line%%=*}
  value=${line#*=}
  if grep -q "^$name=." .env; then
    continue
  fi
  if [ -z "$value" ]; then
    value=$(secret "$name")
  fi
  grep -v "^$name=\$" .env > .env.tmp || true
  mv .env.tmp .env
  printf '%s=%s\n' "$name" "$value" >> .env
  added=$((added + 1))
done < .env.example
chmod 600 .env
if [ "$added" -gt 0 ]; then
  echo "Am completat .env cu $added valori generate."
fi

docker compose up --build -d "$@"

port=$(grep -E '^HTTPS_PORT=' .env | cut -d= -f2)
echo ""
echo "FileGrid: https://localhost:${port:-8443}"
echo "(certificat self-signed - accepta avertismentul browserului)"
echo "Conturi: admin, dispecer, teren, racordari - parolele sunt in .env (PASSWORD_...)"
echo "Alerte (e-mail): http://localhost:8025   Prometheus: http://localhost:9090"
