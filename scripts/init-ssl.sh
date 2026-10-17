#!/usr/bin/env bash
# OCI 최초 인증서 발급. 운영 Nginx 설정은 그대로 둔다.
set -euo pipefail

if (( $# > 1 )); then
  printf 'usage: init-ssl.sh [email]\n' >&2
  exit 2
fi

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
project_dir="$(cd -- "$script_dir/.." && pwd -P)"
DOCKER_BIN="${DOCKER_BIN:-docker}"
DOMAIN='api.airbob.cloud'
EMAIL="${1:-admin@airbob.cloud}"
HEALTH_ATTEMPTS="${HEALTH_ATTEMPTS:-15}"
HEALTH_DELAY_SECONDS="${HEALTH_DELAY_SECONDS:-1}"

for value in "$HEALTH_ATTEMPTS" "$HEALTH_DELAY_SECONDS"; do
  [[ "$value" =~ ^[1-9][0-9]*$ ]] || {
    printf 'health attempts and delay must be positive integers\n' >&2
    exit 2
  }
done
command -v "$DOCKER_BIN" >/dev/null || { printf 'docker executable not found\n' >&2; exit 1; }
for config in nginx.conf conf.d/default.conf.init; do
  [[ -f "$project_dir/docker/nginx/$config" ]] || {
    printf 'required Nginx configuration is missing: %s\n' "$config" >&2
    exit 1
  }
done

work_dir="$(mktemp -d "${TMPDIR:-/tmp}/airbob-ssl.XXXXXX")"
cleanup() {
  local result=$?
  local cid_file
  trap - EXIT INT TERM
  for cid_file in "$work_dir/certbot.cid" "$work_dir/nginx.cid"; do
    if [[ -s "$cid_file" ]]; then
      if ! "$DOCKER_BIN" rm -f "$(cat "$cid_file")" >/dev/null 2>&1; then
        printf 'temporary certificate container cleanup failed: %s\n' "$(cat "$cid_file")" >&2
        (( result != 0 )) || result=1
      fi
    fi
  done
  rm -rf -- "$work_dir"
  exit "$result"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

"$DOCKER_BIN" volume create airbob_certbot-www >/dev/null
"$DOCKER_BIN" volume create airbob_certbot-certs >/dev/null
"$DOCKER_BIN" create --cidfile "$work_dir/nginx.cid" \
  -p 80:80 \
  -v "$project_dir/docker/nginx/nginx.conf:/etc/nginx/nginx.conf:ro" \
  -v "$project_dir/docker/nginx/conf.d/default.conf.init:/etc/nginx/conf.d/default.conf:ro" \
  -v airbob_certbot-www:/var/www/certbot:ro \
  nginx:1.25-alpine >/dev/null
nginx_id="$(cat "$work_dir/nginx.cid")"
"$DOCKER_BIN" start "$nginx_id" >/dev/null

ready=false
for (( attempt=1; attempt<=HEALTH_ATTEMPTS; attempt++ )); do
  if "$DOCKER_BIN" exec "$nginx_id" wget -q -O /dev/null http://127.0.0.1/health; then
    ready=true
    break
  fi
  if (( attempt < HEALTH_ATTEMPTS )); then sleep "$HEALTH_DELAY_SECONDS"; fi
done
if [[ "$ready" != true ]]; then
  printf 'temporary Nginx did not become ready; certificate issuance was not started\n' >&2
  exit 1
fi

"$DOCKER_BIN" run --cidfile "$work_dir/certbot.cid" \
  -v airbob_certbot-www:/var/www/certbot \
  -v airbob_certbot-certs:/etc/letsencrypt \
  certbot/certbot:arm64v8-latest \
  certonly --webroot --webroot-path=/var/www/certbot \
  --email "$EMAIL" --agree-tos --no-eff-email -d "$DOMAIN"

printf '인증서 발급 완료. 운영 Nginx 설정은 유지했습니다.\n'
printf '검토한 앱 이미지 태그로 OCI 배포를 진행하세요:\n'
printf '  IMAGE_TAG=<reviewed-tag> sh "%s/deploy-oci.sh"\n' "$script_dir"
