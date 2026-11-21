#!/bin/bash
# Stop: run the tests for the areas with uncommitted changes under src/.
# Exits 2 with the failures on stderr when they fail. Each code state is tested once,
# so a failure that cannot be fixed (for example Docker not running) cannot block stopping forever.

cd "${CLAUDE_PROJECT_DIR:?}" || exit 0

changed=$({ git diff --name-only HEAD -- src; git ls-files --others --exclude-standard -- src; } | sort -u)
[ -z "$changed" ] && exit 0

state=build/tmp/claude-stop-hook.last
fingerprint=$({ git diff HEAD -- src; git ls-files --others --exclude-standard -z -- src | xargs -0 cat; } 2>/dev/null | shasum | cut -d' ' -f1)
[ "$(cat "$state" 2>/dev/null)" = "$fingerprint" ] && exit 0

# Map each changed file to its area: domain/<name>, another top-level package, or migrations.
areas=$(echo "$changed" | sed -nE \
  -e 's#^src/(main|test)/java/(kr/kro/airbob/domain/[^/]+)/.*#\2#p' \
  -e 's#^src/(main|test)/java/(kr/kro/airbob/[^/]+)/.*#\2#p' \
  -e 's#^src/main/resources/db/migration/.*#kr/kro/airbob/migration#p' | sort -u)

args=()
for area in $areas; do
  [ -d "src/test/java/$area" ] && args+=(--tests "$(echo "$area" | tr / .)*")
done
[ ${#args[@]} -eq 0 ] && exit 0

mkdir -p "$(dirname "$state")" && echo "$fingerprint" > "$state"

if ! out=$(./gradlew test "${args[@]}" 2>&1); then
  {
    echo "Tests failed for the changed areas (./gradlew test ${args[*]}):"
    echo "$out" | grep -vE '^Picked up JAVA_TOOL_OPTIONS|UP-TO-DATE$' | tail -60
  } >&2
  exit 2
fi
