#!/usr/bin/env bash
# Run zap/automation.yaml with a ZAP release against the local server.
#
#   ZAP_DIR=<folder with zap-2.17.0.jar> zap/run_zap.sh <name> <seed HAR>
#
# The server must be running on TARGET (default http://127.0.0.1:8080). Reports <name>.json/.md, the ZAP
# log and a fresh ZAP home go to work/zap/<name>/. Output goes to files only, never to a pipe.
set -euo pipefail

name=${1:?usage: zap/run_zap.sh <name> <seed HAR>}
seed=${2:?usage: zap/run_zap.sh <name> <seed HAR>}
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
: "${ZAP_DIR:?set ZAP_DIR to the folder that holds zap-<version>.jar}"

native() { # absolute path in the form Java understands (Windows form under Git Bash)
  local abs
  abs=$(cd "$(dirname "$1")" && pwd)/$(basename "$1")
  if command -v cygpath > /dev/null; then cygpath -m "$abs"; else printf '%s\n' "$abs"; fi
}

out=$root/work/zap/$name
if [ -e "$out" ]; then
  echo "$out exists: pick another name" >&2
  exit 1
fi
jars=("$ZAP_DIR"/zap-*.jar)
[ -f "${jars[0]}" ] || { echo "no zap-*.jar in $ZAP_DIR" >&2; exit 1; }
[ -f "$seed" ] || { echo "no seed HAR at $seed" >&2; exit 1; }
mkdir -p "$out"

export TARGET=${TARGET:-http://127.0.0.1:8080}
export SEED_HAR REPORT_DIR REPORT_NAME=$name
SEED_HAR=$(native "$seed")
REPORT_DIR=$(native "$out")

status=0
java -Xmx2g -jar "$(native "${jars[0]}")" -cmd -notel -dir "$(native "$out/home")" \
  -config start.checkForUpdates=false -autorun "$(native "$root/zap/automation.yaml")" \
  > "$out/zap.log" 2>&1 || status=$?
echo "ZAP exit status $status, reports and log in $out"
exit "$status"
