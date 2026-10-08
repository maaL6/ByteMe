#!/usr/bin/env bash
set -euo pipefail
task_backend_dir="$(cd "$(dirname "$0")/.." && pwd)"
cd "$task_backend_dir"
if [[ -n "${SA_JAVA_HOME:-}" ]]; then
  task_java_home="$SA_JAVA_HOME"
elif [[ -x /usr/libexec/java_home ]]; then
  task_java_home="$(/usr/libexec/java_home -v 25)"
elif [[ -n "${JAVA_HOME:-}" ]]; then
  task_java_home="$JAVA_HOME"
else
  echo "Cần JAVA_HOME hoặc SA_JAVA_HOME trỏ JDK 25" >&2
  exit 1
fi
# ByteMe dùng Maven Wrapper; backend SA giữ script Maven riêng.
if [[ -f "$task_backend_dir/mvnw" ]]; then
  task_maven=(bash "$task_backend_dir/mvnw")
else
  task_maven=("$task_backend_dir/scripts/maven.sh")
fi
# Chọn Python đã cài cryptography; mock venv là fallback cho workspace SA.
if [[ -n "${AUTH_DEV_PYTHON:-}" ]]; then
  task_python="$AUTH_DEV_PYTHON"
elif [[ -x "$task_backend_dir/.venv/bin/python" ]]; then
  task_python="$task_backend_dir/.venv/bin/python"
elif [[ -x "$task_backend_dir/../tools/mock-api/.venv/bin/python" ]]; then
  task_python="$task_backend_dir/../tools/mock-api/.venv/bin/python"
else
  task_python=python3
fi
"$task_python" "$task_backend_dir/scripts/generate-keys.py"
env JAVA_HOME="$task_java_home" SA_JAVA_HOME="$task_java_home" "${task_maven[@]}" -q -DskipTests test-compile dependency:build-classpath -DincludeScope=test -Dmdep.outputFile=target/classpath.txt
task_classpath="$task_backend_dir/target/test-classes:$task_backend_dir/target/classes:$(cat "$task_backend_dir/target/classpath.txt")"
exec "$task_java_home/bin/java" -cp "$task_classpath" org.example.dev.LocalTokenCli "$task_backend_dir/.local"
