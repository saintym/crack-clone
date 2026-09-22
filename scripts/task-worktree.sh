#!/usr/bin/env bash
# 작업(Txx)별 git worktree 관리 스크립트. 사용법은 docs/PARALLEL.md 참고.
#
#   scripts/task-worktree.sh create T07   # 최신 main에서 작업 브랜치 + worktree 생성
#   scripts/task-worktree.sh path T07     # worktree 경로 출력
#   scripts/task-worktree.sh remove T07   # worktree 삭제 (브랜치가 main에 머지됐으면 브랜치도 삭제)
#   scripts/task-worktree.sh list         # worktree 목록
#   scripts/task-worktree.sh status       # 작업별 상태 (main 기준 작업 파일)
set -euo pipefail

REPO_ROOT="$(git -C "$(dirname "$0")" rev-parse --show-toplevel)"
MAIN_ROOT="$(git -C "$REPO_ROOT" worktree list --porcelain | awk 'NR==1 {print $2}')"
WT_BASE="${CRACK_WT_BASE:-$(dirname "$MAIN_ROOT")/$(basename "$MAIN_ROOT")-wt}"
BASE_BRANCH="${CRACK_BASE_BRANCH:-main}"

die() { echo "error: $*" >&2; exit 1; }

task_file() {
  local id="$1"
  local f
  f="$(ls "$MAIN_ROOT"/docs/tasks/"$id"-*.md 2>/dev/null | head -1 || true)"
  [[ -n "$f" ]] || die "작업 파일을 찾을 수 없습니다: docs/tasks/$id-*.md"
  echo "$f"
}

task_branch() {
  local f
  f="$(task_file "$1")"
  sed -n 's/^- \*\*브랜치\*\*: `\(.*\)`.*/\1/p' "$f" | head -1
}

cmd_create() {
  local id="$1" branch dir
  branch="$(task_branch "$id")"
  [[ -n "$branch" ]] || die "$id 작업 파일에 브랜치 이름이 없습니다"
  dir="$WT_BASE/$id"
  [[ ! -e "$dir" ]] || die "이미 존재합니다: $dir"

  if git -C "$MAIN_ROOT" remote get-url origin >/dev/null 2>&1; then
    git -C "$MAIN_ROOT" fetch -q origin "$BASE_BRANCH" || true
  fi
  local start="$BASE_BRANCH"
  if git -C "$MAIN_ROOT" rev-parse -q --verify "origin/$BASE_BRANCH" >/dev/null &&
     git -C "$MAIN_ROOT" merge-base --is-ancestor "$BASE_BRANCH" "origin/$BASE_BRANCH"; then
    start="origin/$BASE_BRANCH"   # 로컬 main이 뒤처져 있으면 원격 기준으로 딴다
  fi

  mkdir -p "$WT_BASE"
  if git -C "$MAIN_ROOT" rev-parse -q --verify "refs/heads/$branch" >/dev/null; then
    git -C "$MAIN_ROOT" worktree add "$dir" "$branch"          # 이어서 작업
  else
    git -C "$MAIN_ROOT" worktree add --no-track -b "$branch" "$dir" "$start"
  fi

  # gitignore된 로컬 설정만 복사한다. 실제 시나리오 데이터(data/)는 복사하지 않는다.
  local yml="crack-backend/src/main/resources/application.yml"
  if [[ -f "$MAIN_ROOT/$yml" ]]; then
    cp "$MAIN_ROOT/$yml" "$dir/$yml"
  fi

  echo
  echo "worktree: $dir"
  echo "branch:   $branch (from $start)"
  echo "다음 단계: cd \"$dir\" && claude   →  \"$id 진행해\""
}

cmd_path() { echo "$WT_BASE/$1"; }

cmd_remove() {
  local id="$1" dir branch
  dir="$WT_BASE/$id"
  branch="$(task_branch "$id")"
  [[ -d "$dir" ]] || die "worktree가 없습니다: $dir"
  git -C "$MAIN_ROOT" worktree remove "$dir"
  if [[ -n "$branch" ]] && git -C "$MAIN_ROOT" branch --merged "$BASE_BRANCH" --format='%(refname:short)' | grep -qx "$branch"; then
    git -C "$MAIN_ROOT" branch -d "$branch"
    echo "머지된 브랜치 삭제: $branch"
  else
    echo "브랜치 유지(아직 $BASE_BRANCH에 머지되지 않음): $branch"
  fi
}

cmd_list() { git -C "$MAIN_ROOT" worktree list; }

cmd_status() {
  grep -H '^- \*\*상태\*\*' "$MAIN_ROOT"/docs/tasks/T*.md |
    sed -E 's#.*/(T[0-9]+)-[^:]*:- \*\*상태\*\*: *#\1  #'
}

case "${1:-}" in
  create) [[ -n "${2:-}" ]] || die "작업 ID가 필요합니다"; cmd_create "$2" ;;
  path)   [[ -n "${2:-}" ]] || die "작업 ID가 필요합니다"; cmd_path "$2" ;;
  remove) [[ -n "${2:-}" ]] || die "작업 ID가 필요합니다"; cmd_remove "$2" ;;
  list)   cmd_list ;;
  status) cmd_status ;;
  *) sed -n '2,8p' "$0"; exit 1 ;;
esac
