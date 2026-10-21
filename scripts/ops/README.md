# OpenVie backup & restore runbook

Operator-owned tooling for the production data set. Nothing in the platform runs
these scripts: **schedules are entirely operator-owned** (cron/systemd/your
backup orchestrator). See also `docs/DEPLOYMENT.md` §9.

| Script | Purpose |
| --- | --- |
| `scripts/ops/backup.sh` | Timestamped backup of PostgreSQL, the SeaweedFS volumes, and `.env.production`, plus a checksum manifest. |
| `scripts/ops/restore.sh` | Checksum-verified restore of one or all of those artifacts. |

Both scripts must be run from the repository root (they resolve paths relative to
their own location and then `cd` to the repo root). They only need `docker`,
`tar`, `gzip`, and `openssl`/`shasum`/`sha256sum` — no third-party tooling.

## What is backed up

`backup.sh` writes `<BACKUP_DIR>/<YYYYMMDDTHHMMSSZ>/` containing:

| Artifact | Contents |
| --- | --- |
| `postgres.dump` | `pg_dump -Fc` of `$POSTGRES_DB` from the running `postgres` container. |
| `seaweedfs.tar.gz` | tar.gz of the three named volumes `openvie_seaweedfs_master`, `openvie_seaweedfs_volume`, `openvie_seaweedfs_filer`, read read-only through a throwaway `alpine` container. |
| `env.production.bak` | Copy of the production env file (contains secrets — store encrypted/off-host). |
| `MANIFEST.txt` | UTC timestamp, hostname, git revision, and size + sha256 for every artifact. |

`restore.sh` verifies every requested artifact against `MANIFEST.txt` **before**
restoring anything and aborts on mismatch, so a truncated or tampered backup
never reaches a live system.

## Required environment

Load the production env file before running (it also satisfies Compose
interpolation for `exec`):

```bash
set -a; . ./.env.production; set +a
```

| Variable | Used by | Notes |
| --- | --- | --- |
| `POSTGRES_USER` | both | Required; fails loudly if unset. |
| `POSTGRES_DB` | both | Required; fails loudly if unset. Override it for a drill (see below). |
| `ENV_FILE` | backup (source), restore (destination) | Default `.env.production`. Must exist for `backup.sh`. |
| `BACKUP_DIR` | backup | Backup root when `--output` is not passed. Default `./backups`. |
| `COMPOSE_FILE` | both | Default `docker-compose.prod.yml`. |
| `COMPOSE_PROJECT_NAME` | both | Default `openvie`; determines the `*_seaweedfs_*` volume names. |
| `ALPINE_IMAGE` | both | Default `alpine:3`; the throwaway volume reader/writer. |

`restore.sh` also requires `--from DIR` and refuses to run without `--yes`.

## Scheduling (operator-owned)

There are no built-in timers. Pick one of these and point the off-host copy step
at the output directory (no cloud upload is implemented here).

### crontab

```cron
# Nightly OpenVie backup at 02:30 UTC, keeping the 7 most recent.
30 2 * * * cd /opt/openvie && set -a && . ./.env.production && set +a && ./scripts/ops/backup.sh --output /var/backups/openvie --keep 7 >>/var/log/openvie-backup.log 2>&1
```

`--keep 7` is the retention flag: it prunes only directories under the backup
root that match the script's own `YYYYMMDDTHHMMSSZ` naming pattern, keeping the
newest 7. Without `--keep`, nothing is ever deleted.

### systemd timer (alternative)

`/etc/systemd/system/openvie-backup.service`:

```ini
[Unit]
Description=OpenVie backup
After=docker.service
Requires=docker.service

[Service]
Type=oneshot
WorkingDirectory=/opt/openvie
EnvironmentFile=/opt/openvie/.env.production
ExecStart=/opt/openvie/scripts/ops/backup.sh --output /var/backups/openvie --keep 7
```

`/etc/systemd/system/openvie-backup.timer`:

```ini
[Unit]
Description=Nightly OpenVie backup

[Timer]
OnCalendar=*-*-* 02:30:00
Persistent=true
RandomizedDelaySec=300

[Install]
WantedBy=timers.target
```

Enable with `systemctl enable --now openvie-backup.timer` and check with
`systemctl list-timers openvie-backup.timer`.

## RPO / RTO

- **RPO (recovery point objective): up to 24 hours of data loss** with a daily
  backup schedule, for the PostgreSQL + SeaweedFS pair. A backup captures
  Postgres and SeaweedFS at slightly different instants, so they are
  crash-consistent, not transaction-consistent: expect up to one day of lost
  documents, chat history, graph/vector state, and uploaded objects.
- **RTO (recovery time objective): roughly 15–60 minutes** for a small/medium
  install, dominated by `pg_restore` and the container restart plus health
  checks in `docs/DEPLOYMENT.md` §7. Large corpora and cold image pulls push
  this higher.
- Tighten the RPO by increasing the schedule frequency (e.g. hourly) at the cost
  of more disk and more Postgres load; there is no WAL archiving or PITR.
- These numbers are properties of the operator's schedule — OpenVie itself does
  not back anything up, and the platform does not verify that a schedule exists.

## Restore drill (run this before you need it)

Restoring into a scratch database proves the dump is usable without touching
production. Requires the production stack to be running.

```bash
cd /opt/openvie
set -a; . ./.env.production; set +a

# 1. Create the scratch database.
docker compose -f docker-compose.prod.yml exec -T postgres \
  psql -U "$POSTGRES_USER" -d postgres -c \
  'DROP DATABASE IF EXISTS openvie_restore_drill; CREATE DATABASE openvie_restore_drill'

# 2. Restore the newest backup's Postgres dump into it (checksums verified first).
POSTGRES_DB=openvie_restore_drill ./scripts/ops/restore.sh \
  --from /var/backups/openvie/<TIMESTAMP> --only postgres --yes

# 3. Assert the restore looks like production.
docker compose -f docker-compose.prod.yml exec -T postgres \
  psql -U "$POSTGRES_USER" -d openvie_restore_drill -tAc \
  "SELECT (SELECT count(*) FROM pg_tables WHERE schemaname='public')
        || ' tables, '
        || (SELECT count(*) FROM information_schema.columns WHERE table_schema='public')
        || ' columns'"
docker compose -f docker-compose.prod.yml exec -T postgres \
  psql -U "$POSTGRES_USER" -d openvie_restore_drill -tAc \
  'SELECT count(*) FROM flyway_schema_history'

# 4. Drop the scratch database.
docker compose -f docker-compose.prod.yml exec -T postgres \
  psql -U "$POSTGRES_USER" -d postgres -c 'DROP DATABASE openvie_restore_drill'
```

Assertions to record for each drill:

- the table/column counts from step 3 match production (same query against
  `$POSTGRES_DB`), and `flyway_schema_history` has the same maximum `version`;
- `restore.sh` printed `verified ...` for every artifact and exited 0 — a
  checksum failure means the backup is corrupt, so take a fresh one;
- `tar -tzf <backup>/seaweedfs.tar.gz | head` lists `master/`, `volume/`, and
  `filer/` entries, so the object store is not empty.

Run this drill monthly, and after any change to the Compose volume names or the
Postgres image. It is the only cheap proof that a backup can actually be
restored.

## Emergency: recovering a corrupted install

Order of operations matters — restore data before starting the app, or the app
writes over what you just restored.

1. **Stop writes.** `docker compose -f docker-compose.prod.yml --env-file .env.production stop`
   (plain `stop`/`down`; never add `-v`, that deletes the volumes). Keep the
   public edge gateway down so no client can write.
2. **Preserve the broken state** for forensics, in case the incident is
   recoverable: if Postgres still answers, run `./scripts/ops/backup.sh --output /var/backups/openvie-incident`
   first; if it does not, snapshot the volumes by hand
   (`docker run --rm -v openvie_seaweedfs_volume:/data:ro -v "$PWD:/out" alpine:3 tar czf /out/incident-volume.tar.gz -C /data .`).
   Do not skip this — the "corrupt" state is your only evidence.
3. **Pick a backup and verify it.** `./scripts/ops/restore.sh --from /var/backups/openvie/<TIMESTAMP> --dry-run --yes`
   lists the exact commands; the checksum verification that runs first tells you
   whether that backup is intact. Walk backwards through timestamps until one
   verifies.
4. **Restore data:** `./scripts/ops/restore.sh --from <verified-dir> --only postgres --yes`,
   then `--only seaweedfs --yes`, then `--only env --yes` (or run once without
   `--only`). Postgres uses `pg_restore --clean --if-exists`, so objects in the
   target database are dropped and recreated from the dump.
5. **Restart and verify:** `docker compose -f docker-compose.prod.yml --env-file .env.production up -d`
   then run the health checks in `docs/DEPLOYMENT.md` §7, log in as the owner,
   open a document, and run a chat query.
6. **Reconcile what is missing.** Anything written after the backup timestamp is
   gone; if graph extraction logic changed, trigger the reindex described in
   `docs/DEPLOYMENT.md` §9. If the incident was caused by bad data rather than
   hardware, fix the cause before re-enabling clients.
7. **Re-enable writes and re-arm the schedule**, then run the restore drill
   against the restored state to confirm the new baseline is backed up.

## Failure behaviour

- Missing `POSTGRES_USER`/`POSTGRES_DB`, a missing `ENV_FILE`, a missing Compose
  file, or a missing Docker daemon ⇒ the script exits non-zero with an
  `ERROR:` line on stderr. No partial backup directory is reported as complete.
- `restore.sh` without `--yes` exits 3, with an unreadable `--from` directory
  exits 1, and on checksum mismatch exits 1 before touching any live data.
- `--dry-run` prints every command it would run (prefixed `+`) and executes
  nothing.
- All progress logging goes to stderr with a UTC timestamp; every command is
  echoed to stdout, so `--dry-run` output can be diffed or reviewed safely.