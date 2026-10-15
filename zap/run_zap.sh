#!/usr/bin/env bash
# ZAP 배포판으로 로컬 서버를 대상으로 zap/automation.yaml을 실행한다.
#
#   ZAP_DIR=<zap-2.17.0.jar가 있는 폴더> zap/run_zap.sh <이름> <씨앗 HAR>
#   ZAP_DIR=... PLAN=zap/automation-stack.yaml TARGET=http://127.0.0.1:8081 zap/run_zap.sh <이름>
#     (2026-10-15 전체 스택 계획: 씨앗 HAR 대신, ZAP이 기다리는 동안 zap/proxy_seed.py를 --done "work/zap/<이름>/seed.done"
#     으로 실행해 프록시로 업로드를 포함한 요청을 보낸다)
#
# 서버가 TARGET(기본 http://127.0.0.1:8080)에서 실행 중이어야 한다. 보고서 <이름>.json/.md, ZAP 로그, 새로
# 만든 ZAP 홈 폴더는 work/zap/<이름>/에 들어간다. 출력은 파일로만 보내고 파이프로는 절대 보내지 않는다.
# [파일 역할] 5단계 DAST(H9)를 실행하는 스크립트. ZAP을 화면 없이(-cmd) 띄워 zap/automation.yaml 계획(HAR 씨앗
# 가져오기 → spider → passive scan → active scan → JSON·Markdown 보고서)을 돌린다. 결과 요약은 reports/dast/에
# 커밋돼 있다(1차 Low 3건 수정 → 2차 High·Medium 0건, docs/experiments.md 5단계 결과).
# [흐름] 인자·환경 확인 → 출력 폴더 준비(이미 있으면 중단) → automation.yaml이 읽을 환경 변수 설정 → ZAP 실행
# → 종료 코드 전달.
# set -euo pipefail: 명령이 실패하거나(-e), 정의되지 않은 변수를 쓰거나(-u), 파이프 중간이 실패하면(pipefail)
# 바로 멈춘다.
set -euo pipefail

# 인자: 실행 이름(출력 폴더 이름, 필수)과 씨앗 HAR 경로(make_seed_har.py가 만든 것, 기본 계획에서만 필요).
# ${1:?...}는 비었으면 사용법을 출력하고 끝낸다. root는 이 스크립트 위치에서 구한 저장소 루트라 어느 폴더에서 실행해도 같다.
# ZAP_DIR(ZAP 배포판 폴더)은 반드시 지정해야 한다.
name=${1:?usage: zap/run_zap.sh <name> [seed HAR]}
seed=${2:-}
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
# 계획 파일(기본 zap/automation.yaml, 씨앗 HAR 필요). PLAN으로 전체 스택 계획(zap/automation-stack.yaml)을 고른다.
plan=${PLAN:-$root/zap/automation.yaml}
: "${ZAP_DIR:?set ZAP_DIR to the folder that holds zap-<version>.jar}"

# native: 경로를 절대 경로로 바꾸고, Git Bash(Windows)에서는 cygpath -m으로 드라이브 문자로 시작하는 Windows
# 형식(예: X:/...)으로 바꾼다. Windows용 Java는 '/x/...' 같은 MSYS 경로를 이해하지 못하기 때문이다. cygpath가
# 없는 리눅스·macOS에서는 절대 경로를 그대로 쓴다.
native() { # Java가 이해하는 형태의 절대 경로(Git Bash에서는 Windows 형식)
  local abs
  abs=$(cd "$(dirname "$1")" && pwd)/$(basename "$1")
  if command -v cygpath > /dev/null; then cygpath -m "$abs"; else printf '%s\n' "$abs"; fi
}

# 출력 폴더(저장소의 work/zap/<이름>/, 커밋하지 않는 작업 폴더). 이미 있으면 덮어쓰지 않고 멈춰 이전 스캔
# 결과를 보존한다. 그다음 ZAP jar와 씨앗 HAR가 있는지 확인한다.
out=$root/work/zap/$name
if [ -e "$out" ]; then
  echo "$out exists: pick another name" >&2
  exit 1
fi
jars=("$ZAP_DIR"/zap-*.jar)
[ -f "${jars[0]}" ] || { echo "no zap-*.jar in $ZAP_DIR" >&2; exit 1; }
[ -f "$plan" ] || { echo "no plan at $plan" >&2; exit 1; }
# 기본 계획은 씨앗 HAR를 가져오므로 있어야 한다. 전체 스택 계획은 씨앗 HAR를 쓰지 않는다.
if [ -n "$seed" ]; then [ -f "$seed" ] || { echo "no seed HAR at $seed" >&2; exit 1; }; fi
mkdir -p "$out"

# automation.yaml의 ${TARGET}, ${SEED_HAR}, ${REPORT_DIR}, ${REPORT_NAME} 자리에 들어갈 환경 변수. 대상은
# 기본값이 로컬 서버이고(계획 파일 주석: 원격 호스트에는 쓰지 않는다), 경로는 Java가 읽을 수 있는 형태로 바꿔
# 넘긴다.
export TARGET=${TARGET:-http://127.0.0.1:8080}
export SEED_HAR="" SEED_DONE REPORT_DIR REPORT_NAME=$name
if [ -n "$seed" ]; then SEED_HAR=$(native "$seed"); fi
REPORT_DIR=$(native "$out")
# 전체 스택 계획의 delay 작업이 기다리는 파일. zap/proxy_seed.py --done에 같은 경로를 준다.
SEED_DONE="$REPORT_DIR/seed.done"

# ZAP 실행: 힙 2 GB, 텔레메트리 끔(-notel), 업데이트 확인 끔, 실행마다 새 ZAP 홈 폴더(-dir)를 써서 이전
# 설정·세션이 섞이지 않게 한다. 출력은 zap.log 파일로만 보낸다. set -e 상태에서도 ZAP이 실패하면 스크립트가
# 바로 죽지 않도록 '|| status=$?'로 종료 코드를 받아 두고, 안내를 출력한 뒤 그 코드로 끝낸다.
status=0
# -port: ZAP 자체 프록시 포트가 8080에서 도는 서버와 겹치면 안 된다(기본 8099, ZAP_PORT로 바꿀 수 있다).
java -Xmx2g -jar "$(native "${jars[0]}")" -cmd -notel -port "${ZAP_PORT:-8099}" -dir "$(native "$out/home")" \
  -config start.checkForUpdates=false -autorun "$(native "$plan")" \
  > "$out/zap.log" 2>&1 || status=$?
echo "ZAP exit status $status, reports and log in $out"
exit "$status"
