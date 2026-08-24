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
debezium-config
docker/debezium
docker/kafka
docker/mysql/init
logstash
monitoring
nginx
scripts
"

for asset in $managed_files; do
  if [ ! -f "$source_repository/$asset" ]; then
    echo "required deployment asset is missing: $asset" >&2
    exit 1
  fi
  mkdir -p "$target_directory/$(dirname "$asset")"
  rsync --archive --checksum "$source_repository/$asset" "$target_directory/$asset"
done

for asset in $managed_directories; do
  if [ ! -d "$source_repository/$asset" ]; then
    echo "required deployment asset directory is missing: $asset" >&2
    exit 1
  fi
  mkdir -p "$target_directory/$asset"
  rsync --archive --checksum --delete \
    "$source_repository/$asset/" \
    "$target_directory/$asset/"
done

echo "reviewed OCI deployment assets synchronized"
