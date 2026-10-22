#!/usr/bin/env bash
# Copyright 2026 LinkedNodeDigital
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

#
# OpenVie – production restore.
#
# Restores a backup produced by scripts/ops/backup.sh: PostgreSQL
# (pg_restore --clean --if-exists), the SeaweedFS named volumes, and/or the
# production env file. Every artifact is checksum-verified against
# MANIFEST.txt BEFORE anything is restored; a mismatch aborts the run.
#
# Runbook and restore drill: scripts/ops/README.md
#
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
ROOT_DIR=$(cd "$SCRIPT_DIR/../.." && pwd)
cd "$ROOT_DIR"

if [ -z "${ENV_FILE:-}" ]; then
  if [ -f .env.production ]; then
    ENV_FILE=.env.production
  elif [ -f .env ]; then
    ENV_FILE=.env
  else
    ENV_FILE=.env.production
  fi
fi

if [ -z "${COMPOSE_FILE:-}" ]; then
  if [ -f docker-compose.prod.yml ] && docker compose -f docker-compose.prod.yml ps -q 2>/dev/null | grep -q .; then
    COMPOSE_FILE=docker-compose.prod.yml
  elif [ -f docker-compose.yml ] && docker compose -f docker-compose.yml ps -q 2>/dev/null | grep -q .; then
    COMPOSE_FILE=docker-compose.yml
  elif [ -f docker-compose.prod.yml ]; then
    COMPOSE_FILE=docker-compose.prod.yml
  else
    COMPOSE_FILE=docker-compose.yml
  fi
fi
PROJECT_NAME="${COMPOSE_PROJECT_NAME:-openvie}"
VOL_MASTER="${PROJECT_NAME}_seaweedfs_master"
VOL_VOLUME="${PROJECT_NAME}_seaweedfs_volume"
VOL_FILER="${PROJECT_NAME}_seaweedfs_filer"
ALPINE_IMAGE="${ALPINE_IMAGE:-alpine:3}"

FROM=""
ONLY="all"
CONFIRMED=0
DRY_RUN=0

usage() {
  cat <<'EOF'
Usage: scripts/ops/restore.sh --from DIR [options]

Restore an OpenVie backup created by scripts/ops/backup.sh. The manifest
checksums are verified before anything is restored; a mismatch aborts.

Options:
  --from DIR     (required) Backup directory to restore from.
  --only WHICH   Restore only one part: postgres | seaweedfs | env
                 (default: all three).
  --yes          Confirm the destructive restore. Without it the script
                 refuses and exits non-zero.
  --dry-run      Print every command that would be run, execute nothing.
  --help         Show this help and exit.

Environment:
  POSTGRES_USER   (required for postgres restores) PostgreSQL role.
  POSTGRES_DB     (required for postgres restores) PostgreSQL database.
  ENV_FILE        Path the env copy is restored to (default: .env.production).
  COMPOSE_FILE    Compose file (default: docker-compose.prod.yml).
  COMPOSE_PROJECT_NAME  Compose project name (default: openvie).
  ALPINE_IMAGE    Throwaway image for volume restores (default: alpine:3).

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

# run_in <src> <cmd...>  – run cmd with stdin from src
run_in() {
  src=$1; shift
  show "$* < $src"
  if [ "$DRY_RUN" -eq 1 ]; then return 0; fi
  log "exec: $* < $src"
  "$@" < "$src"
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

while [ $# -gt 0 ]; do
  case "$1" in
    --from)  [ $# -ge 2 ] || die "--from requires a directory"; FROM=$2; shift 2 ;;
    --only)  [ $# -ge 2 ] || die "--only requires postgres|seaweedfs|env"; ONLY=$2; shift 2 ;;
    --yes)   CONFIRMED=1; shift ;;
    --dry-run) DRY_RUN=1; shift ;;
    --help|-h) usage; exit 0 ;;
    *) die "unknown argument: $1 (try --help)" ;;
  esac
done

case "$ONLY" in
  all|postgres|seaweedfs|env) ;;
  *) die "--only must be one of: all, postgres, seaweedfs, env (got: $ONLY)" ;;
esac

# --- Preflight --------------------------------------------------------------
if [ -z "$FROM" ]; then
  log "ERROR: --from DIR is required (try --help)"
  exit 2
fi
[ -d "$FROM" ] || die "backup directory not found: $FROM"
MANIFEST="$FROM/MANIFEST.txt"
[ -f "$MANIFEST" ] || die "manifest not found: $MANIFEST (not an OpenVie backup directory)"
[ -f "$COMPOSE_FILE" ] || die "compose file not found: $COMPOSE_FILE (run from the repo root)"

# --- Verify checksums BEFORE touching anything ------------------------------
verify_artifact() {
  artifact=$1
  line=$(awk -v a="$artifact" '$1 == a && NF == 3 {print $2, $3}' "$MANIFEST")
  if [ -z "$line" ]; then
    die "artifact '$artifact' is missing from $MANIFEST"
  fi
  expected_sha=$(printf '%s' "$line" | awk '{print $2}')
  if [ ! -f "$FROM/$artifact" ]; then
    die "artifact file missing: $FROM/$artifact"
  fi
  actual_sha=$(sha256_of "$FROM/$artifact")
  if [ "$expected_sha" != "$actual_sha" ]; then
    die "checksum mismatch for $artifact: manifest=$expected_sha actual=$actual_sha — aborting, backup is corrupt"
  fi
  log "verified $artifact (sha256 $actual_sha)"
}

ARTIFACTS=""
case "$ONLY" in
  all) ARTIFACTS="postgres.dump seaweedfs.tar.gz env.production.bak" ;;
  postgres) ARTIFACTS="postgres.dump" ;;
  seaweedfs) ARTIFACTS="seaweedfs.tar.gz" ;;
  env) ARTIFACTS="env.production.bak" ;;
esac
for artifact in $ARTIFACTS; do
  verify_artifact "$artifact"
done

# --- Confirmation guard -----------------------------------------------------
if [ "$CONFIRMED" -ne 1 ]; then
  log "ERROR: refusing to restore without --yes (this is destructive)"
  exit 3
fi

if [ "$ONLY" = "postgres" ] || [ "$ONLY" = "all" ]; then
  if [ -f "$ENV_FILE" ]; then
    set -a
    # shellcheck disable=SC1090
    . "./$ENV_FILE"
    set +a
  fi
  POSTGRES_USER="${POSTGRES_USER:-openvie}"
  POSTGRES_DB="${POSTGRES_DB:-openvie}"
  if [ "$DRY_RUN" -eq 0 ]; then
    if ! docker compose -f "$COMPOSE_FILE" exec -T postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "SELECT 1" >/dev/null 2>&1; then
      if docker compose -f "$COMPOSE_FILE" exec -T postgres psql -U cacanode -d cacanode -c "SELECT 1" >/dev/null 2>&1; then
        log "detected legacy role 'cacanode'; using cacanode"
        POSTGRES_USER=cacanode
        POSTGRES_DB=cacanode
      fi
    fi
  fi
fi
command -v docker >/dev/null 2>&1 || die "docker is not on PATH"
if [ "$DRY_RUN" -eq 0 ]; then
  docker compose version >/dev/null 2>&1 || die "docker compose plugin is not available"
fi

log "restore starting: from=$FROM only=$ONLY dry_run=$DRY_RUN"

# --- PostgreSQL -------------------------------------------------------------
if [ "$ONLY" = "postgres" ] || [ "$ONLY" = "all" ]; then
  log "restoring PostgreSQL database '$POSTGRES_DB' as role '$POSTGRES_USER' (clean + if-exists)"
  run_in "$FROM/postgres.dump" \
    docker compose -f "$COMPOSE_FILE" exec -T postgres \
    pg_restore --clean --if-exists -U "$POSTGRES_USER" -d "$POSTGRES_DB"
fi

# --- SeaweedFS volumes ------------------------------------------------------
if [ "$ONLY" = "seaweedfs" ] || [ "$ONLY" = "all" ]; then
  log "restoring SeaweedFS volumes: $VOL_MASTER $VOL_VOLUME $VOL_FILER"
  run_in "$FROM/seaweedfs.tar.gz" \
    docker run --rm -i \
    -v "$VOL_MASTER:/data/master" \
    -v "$VOL_VOLUME:/data/volume" \
    -v "$VOL_FILER:/data/filer" \
    "$ALPINE_IMAGE" tar xzf - -C /data
fi

# --- Env file ---------------------------------------------------------------
if [ "$ONLY" = "env" ] || [ "$ONLY" = "all" ]; then
  log "restoring env file to $ENV_FILE"
  if [ "$DRY_RUN" -eq 0 ] && [ -f "$ENV_FILE" ]; then
    KEEP="${ENV_FILE}.pre-restore-$(date -u +%Y%m%dT%H%M%SZ)"
    log "preserving current env file as $KEEP"
    cp -p "$ENV_FILE" "$KEEP"
  fi
  run cp -p "$FROM/env.production.bak" "$ENV_FILE"
fi

log "restore complete"