#!/bin/bash
# PostToolUse (Edit|Write): compile the source set of an edited Java file in its Gradle module.
# Modules live at <group>/<module>/ (core/core-api, clients/*, support/*, ...).
# Exits 2 with the compiler output on stderr when compilation fails.

file=$(jq -r '.tool_input.file_path // empty')
root=${CLAUDE_PROJECT_DIR:?}
relative=${file#"$root"/}

[[ "$relative" =~ ^([a-z0-9-]+)/([a-z0-9-]+)/src/(main|test)/java/.+\.java$ ]] || exit 0
module_dir="${BASH_REMATCH[1]}/${BASH_REMATCH[2]}"
module=":${BASH_REMATCH[1]}:${BASH_REMATCH[2]}"
if [ "${BASH_REMATCH[3]}" = main ]; then task="$module:compileJava"; else task="$module:compileTestJava"; fi
[ -f "$root/$module_dir/build.gradle" ] || exit 0

cd "$root" || exit 0
if ! out=$(./gradlew -q "$task" 2>&1); then
  {
    echo "$task failed after editing $relative:"
    echo "$out" | grep -v '^Picked up JAVA_TOOL_OPTIONS' | tail -40
  } >&2
  exit 2
fi
