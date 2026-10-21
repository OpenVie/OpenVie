# Apache APISIX Gateway — OpenVie Core

Apache APISIX acts as the edge gateway (Cổng API) for the OpenVie headless digital platform, fulfilling Tier 1 (API Gateway) requirements in the DX-OS architecture.

## Architecture and Operating Mode

- **Stand-alone YAML Mode:** Routes and configurations are loaded declaratively from `apisix/config.yaml` and `apisix/routes.yaml` (dev) or `apisix/routes.prod.yaml` (prod). No `etcd` or Admin API is required.
- **Single HTTP Entry Point:** Binds to port `8088` (mapped to APISIX port `9080`), serving as the unified ingress for the Next.js web application, Keycloak authentication, and Spring Boot REST APIs.
- **Internal Services Stay Private:** Internal gRPC (`:50051`), Qdrant, SeaweedFS, Postgres, Redis, and RabbitMQ are not published through the gateway.

## Route Inventory

| URI | Name | Priority | Plugins | Upstream Target |
|---|---|---|---|---|
| `/apisix/status` | `edge-status` | 100 | `serverless-pre-function` (healthy response) | Local APISIX |
| `/apisix/prometheus/metrics` | `edge-metrics` | 100 | `serverless-pre-function` (private network restriction) + `public-api` | Local APISIX |
| `/realms/*` | `edge-keycloak-realms` | 20 | `limit-req` (rate: 10, burst: 20) | Keycloak `:8080` |
| `/resources/*` | `edge-keycloak-resources` | 20 | None | Keycloak `:8080` |
| `/js/*` | `edge-keycloak-js` | 20 | None | Keycloak `:8080` |
| `/api/v1/auth/*` | `edge-auth` | 20 | `limit-req` (rate: 5, burst: 10) | Spring API `:8080` |
| `/api/v1/admin/*` | `edge-admin-api` | 15 | `limit-req` (rate: 20, burst: 40) | Spring API `:8080` |
| `/api/v1/*` | `edge-api` | 10 | `limit-req` (rate: 50, burst: 100) | Spring API `:8080` |
| `/admin/*` | `edge-admin-web` | 5 | WebSocket enabled | `frontend-admin:3000` |
| `/*` | `edge-web` | 0 | WebSocket enabled | `frontend:3000` |

## Observability & Metrics

Prometheus metrics are collected across all routes via the `global_rules` plugin configuration.
- **Metrics Path:** `/apisix/prometheus/metrics`
- **Metric Names:** `apisix_http_status`, `apisix_http_latency_ms`, `apisix_bandwidth_bytes`, etc.
- **Scrape Verification:**
  ```bash
  curl -s http://127.0.0.1:8088/apisix/prometheus/metrics | head -n 20
  ```
  *Note:* Access from outside loopback or private ranges is rejected (404) by design to prevent exposing internal metrics to the public internet.

## Operational Rules & Caveats

1. **The `#END` Sentinel:** Every route configuration file (`routes.yaml` / `routes.prod.yaml`) must end with the literal comment `#END` on its final line. Without this sentinel, APISIX's YAML configuration provider silently fails to parse routes.
2. **Configuration Reloads:** In standalone YAML mode, modifying route files requires restarting the container:
   ```bash
   docker compose restart apisix
   ```
3. **Authentication Boundary:** APISIX intentionally does not enforce `key-auth` or `jwt-auth` plugins. In OpenVie, identity and permissions are resolved dynamically against the database on every request (`JwtAuthFilter` supports both user JWTs and `ovk_...` machine API keys). Applying static key-auth at the gateway would bypass workspace-level tenancy rules and break bearer authentication. Authenticated traffic rate limiting is handled per tenant/user in Redis via `ConsumerRateLimitFilter`.
