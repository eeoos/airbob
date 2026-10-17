#!/bin/sh

set -eu

if [ "$#" -ne 2 ]; then
  echo "usage: sync-oci-deployment-assets.sh SOURCE_REPOSITORY TARGET_DIRECTORY" >&2
  exit 2
fi

source_repository="$1"
target_directory="$2"

if [ ! -d "$source_repository" ]; then
  echo "source repository is not a directory" >&2
  exit 1
fi

if ! command -v rsync >/dev/null 2>&1; then
  echo "rsync is required to synchronize deployment assets" >&2
  exit 1
fi

source_repository="$(cd "$source_repository" && pwd -P)"
mkdir -p "$target_directory"
target_directory="$(cd "$target_directory" && pwd -P)"

if [ "$source_repository" = "$target_directory" ] || [ "$target_directory" = "/" ]; then
  echo "refusing unsafe deployment asset target" >&2
  exit 1
fi

managed_files="
docker-compose.oci.yml
"

managed_directories="
src/main/resources/db/migration
docker/debezium
docker/elasticsearch
docker/kafka
docker/mysql/init
docker/logstash
monitoring
docker/nginx
"

managed_scripts="
deploy-oci.sh
sync-oci-deployment-assets.sh
preflight-reservation-inventory-cutover.sh
reindex-accommodations.sh
init-ssl.sh
"

# Check the complete input before changing any deployed asset.
for asset in $managed_files; do
  if [ ! -f "$source_repository/$asset" ]; then
    echo "required deployment asset is missing: $asset" >&2
    exit 1
  fi
done

for asset in $managed_directories; do
  if [ ! -d "$source_repository/$asset" ]; then
    echo "required deployment asset directory is missing: $asset" >&2
    exit 1
  fi
done
for script in $managed_scripts; do
  if [ ! -f "$source_repository/scripts/$script" ]; then
    echo "required deployment script is missing: $script" >&2
    exit 1
  fi
done

for asset in $managed_files; do
  mkdir -p "$target_directory/$(dirname "$asset")"
  rsync --archive --checksum "$source_repository/$asset" "$target_directory/$asset"
done
for asset in $managed_directories; do
  mkdir -p "$target_directory/$asset"
  rsync --archive --checksum --delete \
    "$source_repository/$asset/" \
    "$target_directory/$asset/"
done

# scripts/ remains managed: remove old copies of retired/local tools as well.
mkdir -p "$target_directory/scripts"
set -- --archive --checksum --delete --delete-excluded
for script in $managed_scripts; do
  set -- "$@" "--include=/$script"
done
rsync "$@" --exclude='*' "$source_repository/scripts/" "$target_directory/scripts/"

echo "reviewed OCI deployment assets synchronized"
