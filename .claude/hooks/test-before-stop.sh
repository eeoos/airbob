#!/bin/bash
# Stop: run the tests for the areas with uncommitted changes in the Gradle modules.
# core/core-api runs only the test packages of the changed areas; other modules (clients/*,
# support/*, ...) are small and run their whole test task.
# Every changed module also runs spotlessCheck: the edit hook formats files, but only the check
# reports lints such as a non-static wildcard import. A change to the formatting configuration
# (root build.gradle, the version catalog, config/formatter) runs spotlessCheck in every module.
# Exits 2 with the failures on stderr when they fail. Each code state is tested once,
# so a failure that cannot be fixed (for example Docker not running) cannot block stopping forever.

cd "${CLAUDE_PROJECT_DIR:?}" || exit 0

app=core/core-api
sources=(core clients support storage tests)
changed=$({ git diff --name-only HEAD -- "${sources[@]}"; git ls-files --others --exclude-standard -- "${sources[@]}"; } | grep '/src/' | sort -u)
format_config=(build.gradle gradle/libs.versions.toml config/formatter)
format_changed=$({ git diff --name-only HEAD -- "${format_config[@]}"; git ls-files --others --exclude-standard -- "${format_config[@]}"; } | sort -u)
[ -z "$changed" ] && [ -z "$format_changed" ] && exit 0

state=build/tmp/claude-stop-hook.last
fingerprint=$({ git diff HEAD -- "${sources[@]}" "${format_config[@]}"; git ls-files --others --exclude-standard -z -- "${sources[@]}" "${format_config[@]}" | xargs -0 cat; } 2>/dev/null | shasum | cut -d' ' -f1)
[ "$(cat "$state" 2>/dev/null)" = "$fingerprint" ] && exit 0

# Map each changed core-api file to its area: a domain package in the template layout
# (core/domain/<x>, core/api/controller/v1/<x>, core/support/<x>), another core/<x> package,
# another top-level package, or migrations.
areas=$(echo "$changed" | sed -nE \
  -e "s#^$app/src/(main|test)/java/(kr/kro/airbob/core/(domain|support|api/controller/v1)/[^/]+)/.*#\\2#p" \
  -e "s#^$app/src/(main|test)/java/(kr/kro/airbob/core/[^/]+)/.*#\\2#p" \
  -e "s#^$app/src/(main|test)/java/(kr/kro/airbob/[^/]+)/.*#\\2#p" \
  -e "s#^$app/src/main/resources/db/migration/.*#kr/kro/airbob/migration#p" | sort -u)

tasks=()
for module_dir in $(echo "$changed" | sed -nE 's#^((clients|support|storage|tests)/[^/]+)/src/.*#\1#p' | sort -u); do
  [ -f "$module_dir/build.gradle" ] && tasks+=(":${module_dir//\//:}:test")
done

app_args=()
for area in $areas; do
  [ -d "$app/src/test/java/$area" ] && app_args+=(--tests "$(echo "$area" | tr / .)*")
done
# Any production change can break a layer or frozen dependency rule; those tests take seconds.
if echo "$changed" | grep -q "^$app/src/main/java/"; then
  app_args+=(--tests "kr.kro.airbob.architecture*")
fi
# --tests applies to the task right before it, so core-api's filtered task goes last.
[ ${#app_args[@]} -gt 0 ] && tasks+=(":core:core-api:test" "${app_args[@]}")

checks=()
if [ -n "$format_changed" ]; then
  # Without a project path, Gradle runs spotlessCheck in every module that has it.
  checks=(spotlessCheck)
else
  for module_dir in $(echo "$changed" | sed -nE 's#^((core|clients|support|storage|tests)/[^/]+)/src/.*#\1#p' | sort -u); do
    [ -f "$module_dir/build.gradle" ] && checks+=(":${module_dir//\//:}:spotlessCheck")
  done
fi
tasks=("${checks[@]}" "${tasks[@]}")
[ ${#tasks[@]} -eq 0 ] && exit 0

mkdir -p "$(dirname "$state")" && echo "$fingerprint" > "$state"

# --continue runs the tests even when spotlessCheck fails, so one stop reports both.
if ! out=$(./gradlew --continue "${tasks[@]}" 2>&1); then
  {
    echo "spotlessCheck or tests failed for the changed areas (./gradlew --continue ${tasks[*]}):"
    echo "$out" | grep -vE '^Picked up JAVA_TOOL_OPTIONS|UP-TO-DATE$' | tail -60
  } >&2
  exit 2
fi
