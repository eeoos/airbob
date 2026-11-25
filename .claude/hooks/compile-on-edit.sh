#!/bin/bash
# PostToolUse (Edit|Write): format an edited Java file with Spotless (Naver convention), then compile
# the source set it belongs to in its Gradle module. One Gradle run does both.
# Modules live at <group>/<module>/ (core/core-api, clients/*, support/*, ...).
# Exits 2 with the Gradle output on stderr when formatting (for example a non-static wildcard import)
# or compilation fails.

file=$(jq -r '.tool_input.file_path // empty')
root=${CLAUDE_PROJECT_DIR:?}
relative=${file#"$root"/}

[[ "$relative" =~ ^([a-z0-9-]+)/([a-z0-9-]+)/src/(main|test)/java/.+\.java$ ]] || exit 0
module_dir="${BASH_REMATCH[1]}/${BASH_REMATCH[2]}"
module=":${BASH_REMATCH[1]}:${BASH_REMATCH[2]}"
if [ "${BASH_REMATCH[3]}" = main ]; then task="$module:compileJava"; else task="$module:compileTestJava"; fi
[ -f "$root/$module_dir/build.gradle" ] || exit 0

cd "$root" || exit 0
if ! out=$(./gradlew -q spotlessApply -PspotlessIdeHook="$file" "$task" 2>&1); then
  {
    echo "spotlessApply or $task failed after editing $relative:"
    echo "$out" | grep -v '^Picked up JAVA_TOOL_OPTIONS' | tail -40
  } >&2
  exit 2
fi
