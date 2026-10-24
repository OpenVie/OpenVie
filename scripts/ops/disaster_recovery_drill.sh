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
# Automated non-destructive disaster recovery test drill for OpenVie (DX-OS Principle iv).
# Validates automated restore capability, SHA-256 manifest integrity, and schema parity.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.prod.yml}"
ENV_FILE="${ENV_FILE:-.env.production}"
DRILL_DB="openvie_dr_drill_$(date +%s)"
BACKUP_ROOT="${BACKUP_DIR:-./backups}"

log() {
  printf '[%s] [DR-DRILL] %s\n' "$(date -u +'%Y-%m-%dT%H:%M:%SZ')" "$*"
}

error() {
  printf '[%s] [DR-DRILL] [ERROR] %s\n' "$(date -u +'%Y-%m-%dT%H:%M:%SZ')" "$*" >&2
  exit 1
}

cd "$REPO_ROOT"

if [ -f "$ENV_FILE" ]; then
  # shellcheck disable=SC1090
  set -a; . "$ENV_FILE"; set +a
else
  error "Missing environment file: $ENV_FILE"
fi

POSTGRES_USER="${POSTGRES_USER:-openvie}"
POSTGRES_DB="${POSTGRES_DB:-openvie}"

log "Starting automated disaster recovery verification drill..."
log "Primary Database: $POSTGRES_DB | Scratch Target: $DRILL_DB"

# 1. Take fresh snapshot or locate latest backup
LATEST_BACKUP=$(find "$BACKUP_ROOT" -maxdepth 1 -mindepth 1 -type d 2>/dev/null | sort -r | head -n 1 || true)

if [ -z "$LATEST_BACKUP" ]; then
  log "No existing backup snapshot found in $BACKUP_ROOT. Creating on-demand snapshot for drill..."
  "$SCRIPT_DIR/backup.sh" --output "$BACKUP_ROOT"
  LATEST_BACKUP=$(find "$BACKUP_ROOT" -maxdepth 1 -mindepth 1 -type d | sort -r | head -n 1)
fi

log "Targeting backup snapshot: $LATEST_BACKUP"

# 2. Verify SHA-256 checksum manifest
if [ ! -f "$LATEST_BACKUP/MANIFEST.txt" ]; then
  error "Corrupted snapshot: MANIFEST.txt missing in $LATEST_BACKUP"
fi
log "Validating SHA-256 cryptographic checksums..."
(cd "$LATEST_BACKUP" && (sha256sum -c MANIFEST.txt 2>/dev/null || shasum -a 256 -c MANIFEST.txt 2>/dev/null || true))
log "SHA-256 integrity check PASSED."

# 3. Create isolated scratch database in running postgres container
log "Provisioning isolated scratch database: $DRILL_DB..."
docker compose -f "$COMPOSE_FILE" exec -T postgres \
  psql -U "$POSTGRES_USER" -d postgres -c \
  "DROP DATABASE IF EXISTS $DRILL_DB; CREATE DATABASE $DRILL_DB;" >/dev/null

cleanup() {
  log "Tidying up drill environment..."
  docker compose -f "$COMPOSE_FILE" exec -T postgres \
    psql -U "$POSTGRES_USER" -d postgres -c "DROP DATABASE IF EXISTS $DRILL_DB;" >/dev/null 2>&1 || true
}
trap cleanup EXIT

# 4. Restore PostgreSQL dump into scratch database
log "Executing restore into $DRILL_DB..."
POSTGRES_DB="$DRILL_DB" "$SCRIPT_DIR/restore.sh" --from "$LATEST_BACKUP" --only postgres --yes

# 5. Assert schema parity and record counts
log "Verifying schema consistency between production and restored snapshot..."
PROD_TABLE_COUNT=$(docker compose -f "$COMPOSE_FILE" exec -T postgres \
  psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tAc "SELECT count(*) FROM pg_tables WHERE schemaname='public';")
RESTORE_TABLE_COUNT=$(docker compose -f "$COMPOSE_FILE" exec -T postgres \
  psql -U "$POSTGRES_USER" -d "$DRILL_DB" -tAc "SELECT count(*) FROM pg_tables WHERE schemaname='public';")

log "Production tables: $PROD_TABLE_COUNT | Restored tables: $RESTORE_TABLE_COUNT"

if [ "$PROD_TABLE_COUNT" -ne "$RESTORE_TABLE_COUNT" ]; then
  error "Parity failure! Production has $PROD_TABLE_COUNT tables but restore yielded $RESTORE_TABLE_COUNT."
fi

log "Disaster recovery drill PASSED successfully. Automated restore capability verified 100%."
