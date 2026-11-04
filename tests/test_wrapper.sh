#!/usr/bin/env bash
set -u

SOURCE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)/ai-memory-ingester"
WRAPPER_SHELL="${WRAPPER_SHELL:-bash}"
WORK="$(cd -P "$(mktemp -d /tmp/wrapper-test-XXXXXX)" && pwd -P)"
trap 'rm -rf "${WORK}"' EXIT

passed=0
failed=0
failures=()

check() {
  if [[ "$2" == "ok" ]]; then
    passed=$((passed + 1))
  else
    failed=$((failed + 1))
    failures+=("$1")
  fi
}

contains() { case "$1" in *"$2"*) echo ok ;; *) echo no ;; esac; }
lacks() { case "$1" in *"$2"*) echo no ;; *) echo ok ;; esac; }

make_jdk() {
  local name="$1" version="$2" release="${3:-yes}"
  local home="${WORK}/jdks/${name}"
  mkdir -p "${home}/bin"
  printf '#!/bin/sh\ncase "$1" in -version) echo "openjdk version \\"%s\\" 2026-09-15" >&2; exit 0;; esac\necho "JAVA $0 $*"\n' "${version}" > "${home}/bin/java"
  chmod +x "${home}/bin/java"
  if [[ "${release}" == "yes" ]]; then
    printf 'IMPLEMENTOR="Test"\nJAVA_VERSION="%s"\n' "${version}" > "${home}/release"
  fi
}

make_groovy() {
  mkdir -p "${WORK}/groovy/bin" "${WORK}/groovy/lib"
  printf '#!/bin/sh\nexit 0\n' > "${WORK}/groovy/bin/groovy"
  chmod +x "${WORK}/groovy/bin/groovy"
}

make_root() {
  rm -rf "${WORK}/root"
  mkdir -p "${WORK}/root/dist"
  cp "${SOURCE}" "${WORK}/root/ai-memory-ingester"
  : > "${WORK}/root/dist/ai-memory-ingester.jar"
  [[ -n "${1:-}" ]] && printf '%s\n' "$1" > "${WORK}/root/dist/required-java"
  return 0
}

OUT=""
ERR=""
RC=0
run() {
  local path="$1"
  shift
  "${WORK}/run-env.sh" "${path}" "$@"
}

cat > "${WORK}/run-env.sh" <<'INNER'
#!/bin/bash
path="$1"; shift
exec env -i HOME="${HOME}" PATH="${path}" \
  ${EXTRA_ENV:-} \
  "${WRAPPER_SHELL}" "${WORK}/root/ai-memory-ingester" "$@"
INNER
chmod +x "${WORK}/run-env.sh"
export WORK WRAPPER_SHELL

invoke() {
  local path="$1"
  shift
  OUT="$(run "${path}" "$@" 2>"${WORK}/stderr.txt")"
  RC=$?
  ERR="$(cat "${WORK}/stderr.txt")"
  EXTRA_ENV=""
}

BASE="/usr/bin:/bin"

make_jdk openjdk-27 27.0.1
make_jdk openjdk-21 21.0.2
make_jdk openjdk-17 17.0.9
make_jdk openjdk-25 25.0.1
make_jdk legacy 1.8.0_392
make_jdk norelease-27 27.0.1 no
make_groovy

make_root
invoke "${WORK}/jdks/openjdk-27/bin:${WORK}/groovy/bin:${BASE}" datasets
check "suitable java on PATH is used" "$(contains "${OUT}" "JAVA ${WORK}/jdks/openjdk-27/bin/java")"
check "suitable java on PATH prints nothing to stderr" "$([[ -z "${ERR}" ]] && echo ok || echo no)"
check "suitable java on PATH exits 0" "$([[ ${RC} -eq 0 ]] && echo ok || echo no)"
check "the class path holds the jar and the groovy lib" "$(contains "${OUT}" "${WORK}/root/dist/ai-memory-ingester.jar")"
check "the class path holds the groovy lib folder" "$(contains "${OUT}" "${WORK}/groovy/lib/*")"
check "the main class is passed" "$(contains "${OUT}" "PrecompiledRunner datasets")"

make_root
EXTRA_ENV="AI_MEMORY_JAVA_SEARCH=${WORK}/jdks" invoke "${WORK}/jdks/openjdk-21/bin:${WORK}/groovy/bin:${BASE}" datasets
check "java 21 on PATH falls back to the highest suitable java" "$(contains "${OUT}" "JAVA ${WORK}/jdks/openjdk-27/bin/java")"
check "the fallback prints one note" "$(contains "${ERR}" "java 21 at ${WORK}/jdks/openjdk-21/bin/java is older than the required 27; using ${WORK}/jdks/openjdk-27/bin/java")"
check "the note is one line" "$([[ "$(printf '%s\n' "${ERR}" | wc -l | tr -d ' ')" == "1" ]] && echo ok || echo no)"
check "the fallback exits 0" "$([[ ${RC} -eq 0 ]] && echo ok || echo no)"

make_root
rm -rf "${WORK}/small" && mkdir -p "${WORK}/small" && cp -R "${WORK}/jdks/openjdk-21" "${WORK}/jdks/openjdk-17" "${WORK}/small/"
EXTRA_ENV="AI_MEMORY_JAVA_SEARCH=${WORK}/small" invoke "${WORK}/jdks/openjdk-21/bin:${WORK}/groovy/bin:${BASE}" datasets
check "no suitable java exits 3" "$([[ ${RC} -eq 3 ]] && echo ok || echo no)"
check "the message names the required version" "$(contains "${ERR}" "no Java 27 or newer found")"
check "the message lists the java on PATH with its version" "$(contains "${ERR}" "version 21")"
check "the message lists the other candidates" "$(contains "${ERR}" "openjdk-17")"
check "the message names AI_MEMORY_JAVA_HOME" "$(contains "${ERR}" "AI_MEMORY_JAVA_HOME")"
check "nothing is launched when no java is suitable" "$([[ -z "${OUT}" ]] && echo ok || echo no)"

make_root
EXTRA_ENV="AI_MEMORY_JAVA_HOME=${WORK}/jdks/openjdk-27" invoke "${WORK}/jdks/openjdk-21/bin:${WORK}/groovy/bin:${BASE}" datasets
check "a valid AI_MEMORY_JAVA_HOME is used" "$(contains "${OUT}" "JAVA ${WORK}/jdks/openjdk-27/bin/java")"
EXTRA_ENV="AI_MEMORY_JAVA_HOME=${WORK}/jdks/openjdk-21" invoke "${WORK}/jdks/openjdk-27/bin:${WORK}/groovy/bin:${BASE}" datasets
check "an unsuitable AI_MEMORY_JAVA_HOME is an error even when PATH is fine" "$([[ ${RC} -eq 3 ]] && echo ok || echo no)"
check "the error names AI_MEMORY_JAVA_HOME" "$(contains "${ERR}" "AI_MEMORY_JAVA_HOME")"
EXTRA_ENV="AI_MEMORY_JAVA_HOME=${WORK}/does-not-exist" invoke "${WORK}/jdks/openjdk-27/bin:${WORK}/groovy/bin:${BASE}" datasets
check "a missing AI_MEMORY_JAVA_HOME is an error" "$([[ ${RC} -eq 3 ]] && echo ok || echo no)"

make_root 25
invoke "${WORK}/jdks/openjdk-25/bin:${WORK}/groovy/bin:${BASE}" datasets
check "required-java 25 accepts java 25" "$(contains "${OUT}" "JAVA ${WORK}/jdks/openjdk-25/bin/java")"
make_root
EXTRA_ENV="AI_MEMORY_JAVA_SEARCH=${WORK}/small" invoke "${WORK}/jdks/openjdk-25/bin:${WORK}/groovy/bin:${BASE}" datasets
check "a missing required-java means 27" "$([[ ${RC} -eq 3 ]] && echo ok || echo no)"
make_root abc
EXTRA_ENV="AI_MEMORY_JAVA_SEARCH=${WORK}/small" invoke "${WORK}/jdks/openjdk-25/bin:${WORK}/groovy/bin:${BASE}" datasets
check "an invalid required-java means 27" "$([[ ${RC} -eq 3 ]] && echo ok || echo no)"

make_root 8
invoke "${WORK}/jdks/legacy/bin:${WORK}/groovy/bin:${BASE}" datasets
check "a 1.8.0 release file means major 8" "$(contains "${OUT}" "JAVA ${WORK}/jdks/legacy/bin/java")"
make_root
EXTRA_ENV="AI_MEMORY_JAVA_SEARCH=${WORK}/small" invoke "${WORK}/jdks/legacy/bin:${WORK}/groovy/bin:${BASE}" datasets
check "major 8 is too old for 27 and is listed as version 8" "$(contains "${ERR}" "version 8")"

make_root
mkdir -p "${WORK}/shim"
ln -sf "${WORK}/jdks/openjdk-27/bin/java" "${WORK}/shim/java"
invoke "${WORK}/shim:${WORK}/groovy/bin:${BASE}" datasets
check "a symlinked java file resolves to its home and needs no fallback" "$(contains "${OUT}" "JAVA ${WORK}/jdks/openjdk-27/bin/java")"
check "a symlinked java file prints no note" "$([[ -z "${ERR}" ]] && echo ok || echo no)"

make_root
invoke "${WORK}/jdks/norelease-27/bin:${WORK}/groovy/bin:${BASE}" datasets
check "a home without a release file uses java -version" "$(contains "${OUT}" "JAVA ${WORK}/jdks/norelease-27/bin/java")"

make_root
EXTRA_ENV="AI_MEMORY_GROOVY_DEFAULT=${WORK}/no-groovy" invoke "${WORK}/jdks/openjdk-27/bin:${BASE}" datasets
check "missing groovy exits 3" "$([[ ${RC} -eq 3 ]] && echo ok || echo no)"
check "missing groovy prints a clear message" "$(contains "${ERR}" "Groovy not found")"
EXTRA_ENV="GROOVY_HOME=${WORK}/groovy AI_MEMORY_GROOVY_DEFAULT=${WORK}/no-groovy" invoke "${WORK}/jdks/openjdk-27/bin:${BASE}" datasets
check "GROOVY_HOME is used when groovy is not on PATH" "$(contains "${OUT}" "${WORK}/groovy/lib/*")"
EXTRA_ENV="AI_MEMORY_GROOVY_DEFAULT=${WORK}/groovy" invoke "${WORK}/jdks/openjdk-27/bin:${BASE}" datasets
check "the groovy default folder is used as the last fallback" "$(contains "${OUT}" "${WORK}/groovy/lib/*")"

make_root
GOOD="${WORK}/jdks/openjdk-27/bin:${WORK}/groovy/bin:${BASE}"
invoke "${GOOD}" query "x"
check "query gets TieredStopAtLevel" "$(contains "${OUT}" "-XX:TieredStopAtLevel=1")"
check "query gets UseSerialGC" "$(contains "${OUT}" "-XX:+UseSerialGC")"
invoke "${GOOD}" 03 "x"
check "03 gets the flags" "$(contains "${OUT}" "-XX:TieredStopAtLevel=1")"
invoke "${GOOD}" record-event
check "record-event gets no flags" "$(lacks "${OUT}" "-XX:TieredStopAtLevel")"
invoke "${GOOD}" datasets
check "datasets gets no flags" "$(lacks "${OUT}" "-XX:+UseSerialGC")"
invoke "${GOOD}" sweep-memory --workspace-root /x
check "sweep-memory gets no flags" "$(lacks "${OUT}" "-XX:TieredStopAtLevel")"
check "arguments are passed through" "$(contains "${OUT}" "sweep-memory --workspace-root /x")"
invoke "${GOOD}"
check "no arguments still launch" "$([[ ${RC} -eq 0 ]] && echo ok || echo no)"

rm -f "${WORK}/root/dist/ai-memory-ingester.jar"
invoke "${GOOD}" datasets
check "a missing jar keeps its message and exit 1" "$([[ ${RC} -eq 1 ]] && [[ "$(contains "${ERR}" "Missing")" == ok ]] && echo ok || echo no)"

printf 'Wrapper tests (%s): %d passed, %d failed\n' "${WRAPPER_SHELL}" "${passed}" "${failed}"
for ((i = 0; i < ${#failures[@]}; i++)); do printf '  - %s\n' "${failures[$i]}"; done
[[ ${failed} -eq 0 ]]
