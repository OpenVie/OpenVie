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
# Declarative GitOps reconciliation engine for OpenVie (DX-OS Principle iv).
# Synchronizes declared Git repository state to running Docker Compose infrastructure.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.prod.yml}"
ENV_FILE="${ENV_FILE:-.env.production}"
BRANCH="${GITOPS_BRANCH:-main}"

log() {
  printf '[%s] [GITOPS-SYNC] %s\n' "$(date -u +'%Y-%m-%dT%H:%M:%SZ')" "$*"
}

error() {
  printf '[%s] [GITOPS-SYNC] [ERROR] %s\n' "$(date -u +'%Y-%m-%dT%H:%M:%SZ')" "$*" >&2
  exit 1
}

cd "$REPO_ROOT"

log "Starting GitOps automated reconciliation for OpenVie..."
log "Repository root: $REPO_ROOT"
log "Target branch: $BRANCH | Compose spec: $COMPOSE_FILE"

# 1. Verify Git state and declarative configuration
if [ -d .git ]; then
  CURRENT_COMMIT="$(git rev-parse HEAD || echo "unknown")"
  log "Current local commit: $CURRENT_COMMIT"

  if git remote | grep -q 'origin'; then
    log "Fetching latest declarative changes from remote origin..."
    git fetch origin "$BRANCH" --quiet || log "Warning: remote fetch failed, reconciling from local state"
    LATEST_COMMIT="$(git rev-parse "origin/$BRANCH" 2>/dev/null || echo "$CURRENT_COMMIT")"
    if [ "$CURRENT_COMMIT" != "$LATEST_COMMIT" ]; then
      log "Reconciling commit: $CURRENT_COMMIT -> $LATEST_COMMIT"
      git merge --ff-only "origin/$BRANCH" || error "Fast-forward merge failed. Git conflict detected."
    else
      log "Infrastructure is already in sync with Git commit: $CURRENT_COMMIT"
    fi
  fi
else
  log "Not inside a Git repository clone. Proceeding with local declarative manifest reconciliation."
fi

# 2. Check environment file
if [ ! -f "$ENV_FILE" ]; then
  if [ -f .env.production.example ]; then
    log "Notice: $ENV_FILE missing; copying from .env.production.example as baseline"
    cp .env.production.example "$ENV_FILE"
  else
    error "$ENV_FILE and .env.production.example are both missing."
  fi
fi

# 3. Pull / Build and Deploy declarative infrastructure
log "Synchronizing containers with declarative spec in $COMPOSE_FILE..."
docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" up -d --remove-orphans

# 4. Automated health probe validation
log "Validating component readiness and self-healing status..."
MAX_ATTEMPTS=20
ATTEMPT=1
SERVICES_READY=false

while [ $ATTEMPT -le $MAX_ATTEMPTS ]; do
  UNHEALTHY=$(docker compose -f "$COMPOSE_FILE" ps --format json 2>/dev/null | grep -E '"(unhealthy|starting)"' || true)
  if [ -z "$UNHEALTHY" ]; then
    SERVICES_READY=true
    break
  fi
  log "Waiting for services to become healthy (attempt $ATTEMPT/$MAX_ATTEMPTS)..."
  sleep 3
  ATTEMPT=$((ATTEMPT + 1))
done

if [ "$SERVICES_READY" = true ]; then
  log "All services converged to declared healthy state."
else
  log "Warning: Some services still starting or non-healthy. Check 'docker compose ps'."
fi

log "GitOps reconciliation COMPLETED successfully."
