#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
BUILD_DIR="${ROOT}/build/precompiled"
CLASSES_DIR="${BUILD_DIR}/classes"
DIST_DIR="${ROOT}/dist"
VENDOR_DIR="${ROOT}/vendor"
GRAPES_DIR="${HOME}/.groovy/grapes"

mkdir -p "${CLASSES_DIR}" "${DIST_DIR}" "${VENDOR_DIR}"

DEPENDENCIES=(
  "${GRAPES_DIR}/org.xerial/sqlite-jdbc/jars/sqlite-jdbc-3.45.1.0.jar"
  "${GRAPES_DIR}/org.slf4j/slf4j-api/jars/slf4j-api-2.0.12.jar"
  "${GRAPES_DIR}/org.slf4j/slf4j-nop/jars/slf4j-nop-2.0.12.jar"
  "${GRAPES_DIR}/org.apache.pdfbox/pdfbox/jars/pdfbox-2.0.30.jar"
  "${GRAPES_DIR}/org.apache.pdfbox/fontbox/bundles/fontbox-2.0.30.jar"
  "${GRAPES_DIR}/commons-logging/commons-logging/jars/commons-logging-1.2.jar"
)

for dependency in "${DEPENDENCIES[@]}"; do
  if [[ ! -f "${dependency}" ]]; then
    printf 'Missing dependency: %s\n' "${dependency}" >&2
    exit 1
  fi
  cp "${dependency}" "${VENDOR_DIR}/"
done

VENDOR_CLASSPATH="${VENDOR_DIR}/*"
rm -rf "${CLASSES_DIR}"
mkdir -p "${CLASSES_DIR}"

groovyc \
  -cp "${VENDOR_CLASSPATH}" \
  -d "${CLASSES_DIR}" \
  "${ROOT}"/lib/*.groovy \
  "${ROOT}"/01_analyze_archives.groovy \
  "${ROOT}"/02_ingest_archive.groovy \
  "${ROOT}"/03_query_memory.groovy \
  "${ROOT}"/04_compress_memory.groovy \
  "${ROOT}"/05_ingest_chatlog.groovy \
  "${ROOT}"/06_ingest_worklogs.groovy \
  "${ROOT}"/07_sweep_memory.groovy \
  "${ROOT}"/08_record_event.groovy \
  "${ROOT}"/09_reconcile_project.groovy \
  "${ROOT}"/PrecompiledRunner.groovy

jar --create --file "${DIST_DIR}/ai-memory-ingester.jar" -C "${CLASSES_DIR}" .
