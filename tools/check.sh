#!/bin/sh
# Run focused Java regressions against the packaged, ownership-instrumented engine.
# usage: tools/check.sh [CheckClass...]
# BUILD=0 skips rebuilding; SERVER_INSTALL selects an isolated install.
set -eu
cd "$(dirname "$0")/.."
mkdir -p build
install=${SERVER_INSTALL:-build/install-dev}
for check in "$@"; do
  case "$check" in
    ''|*[!A-Za-z0-9_]*) echo "invalid check name: $check" >&2; exit 2 ;;
  esac
  if [ ! -f "tools/$check.java" ]; then
    echo "unknown check: $check" >&2
    exit 2
  fi
done
if [ "${BUILD:-1}" = 1 ]; then
  ./build.sh installDist "-PinstallTo=$install"
fi
check_java_opts=
if [ -f "$install/agent/tickless-fabric-agent.jar" ]; then
  check_java_opts="-javaagent:/workspace/$install/agent/tickless-fabric-agent.jar -Dtickless.fabric.checks=true"
fi
exec docker run --rm --user "$(id -u):$(id -g)" \
  -e CHECK_JAVA_OPTS="$check_java_opts" \
  -e CHECK_TIMEOUT="${CHECK_TIMEOUT:-150}" \
  -v "$PWD:/workspace" -w /workspace \
  eclipse-temurin:27-jdk sh -eu -c '
    install=$1
    shift
    classes=build/checks-focused
    mkdir -p "$classes/logs"
    javac -cp "/workspace/$install/lib/*" -d "$classes" /workspace/tools/*Check.java
    cd "$classes"
    mkdir -p build/checks
    if [ "$#" -eq 0 ]; then
      set -- /workspace/tools/*Check.java
    fi
    for selected in "$@"; do
      check=${selected##*/}
      check=${check%.java}
      source=/workspace/tools/$check.java
      package=$(sed -n "s/^package \([^;]*\);/\1/p" "$source")
      main=${package:+$package.}$check
      if timeout "$CHECK_TIMEOUT" java ${CHECK_JAVA_OPTS:-} -Xmx2g -XX:+UseG1GC \
          -cp ".:/workspace/$install/lib/*" "$main" > "$check.log" 2>&1; then
        echo "PASS $check"
      else
        status=$?
        cat "$check.log"
        echo "FAIL $check (exit $status)" >&2
        exit "$status"
      fi
    done
  ' checks "$install" "$@"
