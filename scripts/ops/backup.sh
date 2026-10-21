#!/usr/bin/env bash
#
# OpenVie – production backup.
#
# Creates a timestamped backup directory containing a PostgreSQL custom-format
# dump, a tar.gz of the SeaweedFS named volumes, a copy of the production env
# file, and a MANIFEST.txt with sizes and sha256 checksums.
#
# Runbook and scheduling: scripts/ops/README.md
#
# The schedules are OPERATOR-OWNED: nothing in the platform runs this script.
#
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
ROOT_DIR=$(cd "$SCRIPT_DIR/../.." && pwd)
cd "$ROOT_DIR"

COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.prod.yml}"
ENV_FILE="${ENV_FILE:-.env.production}"
# Compose project name; volume names are "<project>_seaweedfs_<role>".
PROJECT_NAME="${COMPOSE_PROJECT_NAME:-openvie}"
VOL_MASTER="${PROJECT_NAME}_seaweedfs_master"
VOL_VOLUME="${PROJECT_NAME}_seaweedfs_volume"
VOL_FILER="${PROJECT_NAME}_seaweedfs_filer"
ALPINE_IMAGE="${ALPINE_IMAGE:-alpine:3}"

BACKUP_ROOT="${BACKUP_DIR:-./backups}"
RETENTION=7
RETENTION_EXPLICIT=0
DRY_RUN=0

usage() {
  cat <<'EOF'
Usage: scripts/ops/backup.sh [options]

Create a timestamped backup of the OpenVie production data set under
<BACKUP_DIR>/<UTC-timestamp>/.

Artifacts written:
  postgres.dump        PostgreSQL custom-format dump (pg_dump -Fc)
  seaweedfs.tar.gz     tar.gz of the three SeaweedFS named volumes
  env.production.bak   copy of the production env file
  MANIFEST.txt         UTC time, hostname, git revision, sizes, sha256

Options:
  --output DIR   Backup root directory (default: $BACKUP_DIR or ./backups).
  --keep N       Retention: keep the N most recent backups and delete older
                 ones. Only directories matching this script's own naming
                 pattern (YYYYMMDDTHHMMSSZ) under the backup root are pruned,
                 and pruning only happens when --keep is given explicitly
                 (default value used: 7). Without --keep nothing is deleted.
  --dry-run      Print every command that would be run, execute nothing.
  --help         Show this help and exit.

Environment:
  POSTGRES_USER   (required) PostgreSQL role used by pg_dump.
  POSTGRES_DB     (required) PostgreSQL database used by pg_dump.
  ENV_FILE        Path to the production env file (default: .env.production).
  BACKUP_DIR      Backup root when --output is not given (default: ./backups).
  COMPOSE_FILE    Compose file (default: docker-compose.prod.yml).
  COMPOSE_PROJECT_NAME  Compose project name (default: openvie).
  ALPINE_IMAGE    Throwaway image for volume archiving (default: alpine:3).

Load the production environment before running, for example:
  set -a; . ./.env.production; set +a
EOF
}

log() { printf '[%s] %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*" >&2; }
die() { log "ERROR: $*"; exit 1; }
show() { printf '+ %s\n' "$*"; }

run() {
  show "$*"
  if [ "$DRY_RUN" -eq 1 ]; then return 0; fi
  log "exec: $*"
  "$@"
}

# run_redir <dst> <cmd...>  – run cmd with stdout redirected to dst
run_redir() {
  dst=$1; shift
  show "$* > $dst"
  if [ "$DRY_RUN" -eq 1 ]; then return 0; fi
  log "exec: $* > $dst"
  "$@" > "$dst"
}

sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | awk '{print $1}'
  elif command -v openssl >/dev/null 2>&1; then
    openssl dgst -sha256 "$1" | awk '{print $NF}'
  else
    die "no sha256 tool available (need sha256sum, shasum or openssl)"
  fi
}

size_of() { wc -c < "$1" | tr -d ' '; }

while [ $# -gt 0 ]; do
  case "$1" in
    --output) [ $# -ge 2 ] || die "--output requires a directory"; BACKUP_ROOT=$2; shift 2 ;;
    --keep)   [ $# -ge 2 ] || die "--keep requires a number"; RETENTION=$2; RETENTION_EXPLICIT=1; shift 2 ;;
    --dry-run) DRY_RUN=1; shift ;;
    --help|-h) usage; exit 0 ;;
    *) die "unknown argument: $1 (try --help)" ;;
  esac
done

case "$RETENTION" in
  ''|*[!0-9]*) die "--keep expects a non-negative integer, got: $RETENTION" ;;
esac

# --- Preflight --------------------------------------------------------------
[ -f "$COMPOSE_FILE" ] || die "compose file not found: $COMPOSE_FILE (run from the repo root)"
[ -f "$ENV_FILE" ] || die "env file not found: $ENV_FILE (create it from .env.production.example)"
[ -n "${POSTGRES_USER:-}" ] || die "POSTGRES_USER is not set (try: set -a; . ./$ENV_FILE; set +a)"
[ -n "${POSTGRES_DB:-}" ] || die "POSTGRES_DB is not set (try: set -a; . ./$ENV_FILE; set +a)"
command -v docker >/dev/null 2>&1 || die "docker is not on PATH"
if [ "$DRY_RUN" -eq 0 ]; then
  docker compose version >/dev/null 2>&1 || die "docker compose plugin is not available"
fi

TIMESTAMP=$(date -u +%Y%m%dT%H%M%SZ)
DEST="${BACKUP_ROOT%/}/$TIMESTAMP"

log "backup starting: dest=$DEST dry_run=$DRY_RUN"
show "mkdir -p $DEST"
[ "$DRY_RUN" -eq 1 ] || mkdir -p "$DEST"

# --- PostgreSQL -------------------------------------------------------------
log "dumping PostgreSQL database '$POSTGRES_DB' as role '$POSTGRES_USER'"
run_redir "$DEST/postgres.dump" \
  docker compose -f "$COMPOSE_FILE" exec -T postgres \
  pg_dump -U "$POSTGRES_USER" -Fc "$POSTGRES_DB"

# --- SeaweedFS volumes ------------------------------------------------------
log "archiving SeaweedFS volumes: $VOL_MASTER $VOL_VOLUME $VOL_FILER"
run_redir "$DEST/seaweedfs.tar.gz" \
  docker run --rm \
  -v "$VOL_MASTER:/data/master:ro" \
  -v "$VOL_VOLUME:/data/volume:ro" \
  -v "$VOL_FILER:/data/filer:ro" \
  "$ALPINE_IMAGE" tar czf - -C /data master volume filer

# --- Env file ---------------------------------------------------------------
log "copying $ENV_FILE"
run cp -p "$ENV_FILE" "$DEST/env.production.bak"

# --- Manifest ---------------------------------------------------------------
if [ "$DRY_RUN" -eq 1 ]; then
  show "write $DEST/MANIFEST.txt (timestamp_utc, hostname, git_revision, sizes, sha256)"
else
  HOST=$(hostname 2>/dev/null || echo unknown)
  GIT_REV=$(git rev-parse HEAD 2>/dev/null || echo unknown)
  {
    echo "# OpenVie backup manifest"
    echo "# format: <artifact> <size_bytes> <sha256>"
    echo "# timestamp_utc: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "# hostname: $HOST"
    echo "# git_revision: $GIT_REV"
    echo "# postgres_db: $POSTGRES_DB"
    echo "# postgres_user: $POSTGRES_USER"
    for artifact in postgres.dump seaweedfs.tar.gz env.production.bak; do
      echo "$artifact $(size_of "$DEST/$artifact") $(sha256_of "$DEST/$artifact")"
    done
  } > "$DEST/MANIFEST.txt"
  log "wrote $DEST/MANIFEST.txt"
fi

# --- Retention --------------------------------------------------------------
if [ "$RETENTION_EXPLICIT" -eq 1 ] && [ "$RETENTION" -gt 0 ]; then
  log "retention: keeping $RETENTION backup(s) under ${BACKUP_ROOT%/}"
  if [ "$DRY_RUN" -eq 1 ]; then
    show "find ${BACKUP_ROOT%/} -mindepth 1 -maxdepth 1 -type d -name 20????????T??????Z ! -path $DEST | sort -r | sed -e 1,${RETENTION}d | xargs rm -rf"
  elif [ ! -d "${BACKUP_ROOT%/}" ]; then
    log "backup root does not exist yet, nothing to prune"
  else
    OLD=$(
      find "${BACKUP_ROOT%/}" -mindepth 1 -maxdepth 1 -type d \
        -name '20[0-9][0-9][0-9][0-9][0-9][0-9]T[0-9][0-9][0-9][0-9][0-9][0-9]Z' \
        ! -path "$DEST" | sort -r | sed -e "1,${RETENTION}d"
    )
    if [ -n "$OLD" ]; then
      echo "$OLD" | while IFS= read -r dir; do
        [ -n "$dir" ] || continue
        run rm -rf "$dir"
      done
    else
      log "nothing to prune"
    fi
  fi
fi

log "backup complete: $DEST"