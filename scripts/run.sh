#!/usr/bin/env bash
# Single launcher for every RafenDB entry point.
#   scripts/run server [args]
#   scripts/run client [args]
#   scripts/run driver <processor|optimizer|storage|concurrency|recovery> [--mock=...] [args]
#   scripts/run reset-data
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAR="$ROOT/target/rafendb.jar"

usage() {
  sed -n '3,6p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//' >&2
  exit 2
}

[[ $# -ge 1 ]] || usage
cmd="$1"; shift

if [[ "$cmd" == "reset-data" ]]; then
  rm -rf "$ROOT/data"
  echo "Removed $ROOT/data, next run will recreates it from the seed."
  exit 0
fi

case "$cmd" in
  server) main=org.rafendb.server.ServerMain ;;
  client) main=org.rafendb.client.ClientMain ;;
  driver)
    [[ $# -ge 1 ]] || usage
    comp="$1"; shift
    case "$comp" in
      processor|qp)    main=org.rafendb.processor.driver.ProcessorDriver ;;
      optimizer|qo)    main=org.rafendb.optimizer.driver.OptimizerDriver ;;
      storage|sm)      main=org.rafendb.storage.driver.StorageDriver ;;
      concurrency|ccm) main=org.rafendb.concurrency.driver.ConcurrencyDriver ;;
      recovery|frm)    main=org.rafendb.recovery.driver.RecoveryDriver ;;
      *) usage ;;
    esac
    ;;
  *) usage ;;
esac

if [[ ! -f "$JAR" ]]; then
  echo "target/rafendb.jar not found. Build it via ./mvnw -q package -DskipTests" >&2
  exit 1
fi

# Run from the repo root so relative paths (data/, test-scenarios/...) mean the same thing for everyone.
cd "$ROOT"
exec java -cp "$JAR" "$main" "$@"
