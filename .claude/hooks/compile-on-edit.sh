#!/bin/bash
# PostToolUse (Edit|Write): compile the source set of an edited Java file.
# Exits 2 with the compiler output on stderr when compilation fails.

file=$(jq -r '.tool_input.file_path // empty')
root=${CLAUDE_PROJECT_DIR:?}

case "$file" in
  "$root"/src/main/java/*.java) task=compileJava ;;
  "$root"/src/test/java/*.java) task=compileTestJava ;;
  *) exit 0 ;;
esac

cd "$root" || exit 0
if ! out=$(./gradlew -q "$task" 2>&1); then
  {
    echo "$task failed after editing ${file#"$root"/}:"
    echo "$out" | grep -v '^Picked up JAVA_TOOL_OPTIONS' | tail -40
  } >&2
  exit 2
fi
