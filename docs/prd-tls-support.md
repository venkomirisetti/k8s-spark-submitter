# PRD: TLS Support with Auto-Reload for k8s-spark-submitter

## Overview

Add opt-in TLS support to the Spark Submit REST API server. Certificates are provided as PEM files mounted into the pod (via K8s Secret or ConfigMap). The server auto-reloads certificates when the underlying files are refreshed by the kubelet — no pod restart required.

## Motivation

- Not every K8s cluster runs a service mesh (Istio/Linkerd). Users without mesh have no encryption on the submit API.
- Spark's own web UIs (History Server, etc.) lack cert auto-reload — this is a gap in the ecosystem.
- Cert rotation is standard practice (cert-manager, short-lived certs). The server must handle rotation gracefully without downtime.

## Scope

### In Scope

| Item | Detail |
|------|--------|
| PEM certificate support | `tls.crt` (cert chain) + `tls.key` (private key) in PEM format |
| Opt-in via env vars | TLS disabled by default; enabled when cert/key paths are provided |
| mTLS (optional) | When CA cert path is set, require and verify client certificates |
| Auto-reload on file change | Poller detects cert/key/CA file updates and triggers SSL context reload |
| Symlink-aware detection | Handles K8s atomic symlink swap for Secret/ConfigMap volume mounts |
| Graceful reload | In-flight connections finish on old cert; new connections use new cert |
| Startup validation | Fail fast if TLS is enabled but cert/key/CA files are missing or malformed |

### Out of Scope

| Item | Reason |
|------|--------|
| JKS / PKCS12 keystores | Not K8s-native; PEM is what cert-manager and K8s Secrets produce |
| mTLS enforcement policies (RBAC per-client CN) | Beyond scope — mTLS verifies the client has a cert signed by the trusted CA; per-client authorization is a separate concern |
| Exposing API endpoints on the probe port | Probe port only serves /health and /metrics — no submit API |
| HTTP → HTTPS redirect | No browser users; direct API clients only |
| OCSP stapling / CRL | Overkill for internal cluster traffic |

## Configuration

All via environment variables, consistent with existing `ServerConfig` pattern:

| Env Var | Required | Default | Description |
|---------|----------|---------|-------------|
| `TLS_CERT_PATH` | No | (unset) | Path to PEM certificate chain file. TLS is enabled when both cert and key paths are set. |
| `TLS_KEY_PATH` | No | (unset) | Path to PEM private key file. |
| `TLS_CA_CERT_PATH` | No | (unset) | Path to PEM CA certificate for client verification. When set, mTLS is enabled (clients must present a cert signed by this CA). |
| `PROBE_PORT` | No | `8081` | Plain HTTP port for health/metrics probes (always active, separate from API port). |
| `TLS_CERT_RELOAD_INTERVAL_MS` | No | `30000` | How often (ms) to check for cert file changes. |

**Behavior:**
- Always dual-port: API on `PORT`, probes on `PROBE_PORT`.
- If neither `TLS_CERT_PATH` nor `TLS_KEY_PATH` is set → both ports are plain HTTP.
- If both are set → `PORT` is HTTPS, `PROBE_PORT` stays plain HTTP.
- If `TLS_CA_CERT_PATH` is also set → `PORT` is HTTPS + mTLS, `PROBE_PORT` stays plain HTTP.
- If only one of cert/key is set → fail fast at startup with a clear error message.
- If `TLS_CA_CERT_PATH` is set without cert/key → fail fast (mTLS requires server TLS).

**Port layout (always):**

| Port | Endpoints | TLS off | TLS on | mTLS on |
|------|-----------|---------|--------|---------|
| `PORT` (8080) | `/spark-submit` | HTTP | HTTPS | HTTPS + client cert |
| `PROBE_PORT` (8081) | `/health`, `/metrics` | HTTP | HTTP | HTTP |

Separating probes from the API port ensures health checks and Prometheus scrapes never compete with submission traffic for threads or connections.

## Technical Design

### Certificate Loading

1. Read PEM cert chain and private key from file paths at startup.
2. Build an `SslContextFactory.Server` from the shaded Jetty (`org.sparkproject.jetty`) already available in the Spark base image.
3. If `TLS_CA_CERT_PATH` is set, configure the truststore with the CA cert and enable `setNeedClientAuth(true)`.
4. Create a `ServerConnector` with SSL connection factories instead of the plain HTTP connector.

### Auto-Reload Mechanism

1. A daemon thread polls cert files at the configured interval.
2. Detection strategy: resolve symlinks, then compare file content hash (SHA-256) against last loaded hash.
   - Content hash is more reliable than `lastModified` — covers symlink swaps, in-place writes, and clock skew.
   - Watches all configured files: cert, key, and CA cert (if mTLS enabled).
3. On change detected:
   - Validate new cert/key/CA are parseable before reloading (don't break on a partial write).
   - Call `SslContextFactory.reload()` — Jetty handles atomic swap of the SSL context (including truststore for mTLS).
   - Log cert reload event (subject, expiry, serial) at INFO level.
4. On validation failure:
   - Log at WARN, keep serving with the current cert. Retry on next poll cycle.

### K8s Volume Mount Behavior

When a Secret or ConfigMap backing a volume is updated:
- Kubelet creates a new timestamped directory.
- Atomically swaps the `..data` symlink to point to the new directory.
- File paths remain stable; content changes behind symlinks.

Our poller resolves symlinks before hashing, so it naturally detects both:
- In-place file overwrites (rare, but possible with `subPath` mounts).
- Symlink atomic swaps (standard K8s volume update mechanism).

### Startup Validation

When TLS is enabled, fail fast if:
- Cert or key file does not exist at the configured path.
- Cert is not valid PEM / cannot be parsed as X.509.
- Private key cannot be parsed or doesn't match the cert.
- CA cert path is set but file is missing or not valid PEM.
- CA cert path is set without server cert/key (mTLS requires server TLS).

### Dual-Port Architecture (always)

The server always starts two Jetty connectors, regardless of TLS:

1. **API connector** (`PORT`, default 8080) — serves only `/spark-submit`. HTTP or HTTPS depending on TLS config.
2. **Probe connector** (`PROBE_PORT`, default 8081) — serves only `/health` and `/metrics`. Always plain HTTP.

This ensures:
- Probe traffic never competes with submission traffic for threads/connections.
- Kubelet and Prometheus always reach health/metrics without TLS complexity.
- Port isolation is consistent across all deployment modes.

```yaml
ports:
  - name: api
    containerPort: 8080
  - name: probes
    containerPort: 8081

livenessProbe:
  httpGet:
    path: /health
    port: probes

# Prometheus annotation
prometheus.io/port: "8081"
```

## Implementation Plan

1. **Add config to `ServerConfig`** — new `Tls` object (cert/key/CA paths, reload interval) and `PROBE_PORT` in `Server` object.
2. **Refactor `SparkSubmitServer` to dual-port** — API connector serves only `/spark-submit`; probe connector serves `/health` and `/metrics`. This is a standalone change that works without TLS.
3. **Create `TlsContextLoader`** — reads PEM files, builds `SslContextFactory.Server`, configures truststore for mTLS when CA cert is set, validates cert/key pair.
4. **Create `CertReloadWatcher`** — daemon thread that polls cert/key/CA files, detects changes via SHA-256 hash, triggers `SslContextFactory.reload()`.
5. **Wire TLS into API connector** — if TLS enabled, API connector uses SSL connection factories; probe connector always stays plain HTTP.
6. **Add tests** — unit tests for PEM loading, mTLS enforcement, reload detection, startup validation failures, and dual-port routing.
7. **Update README** — document configuration with K8s Secret/ConfigMap examples and deployment YAML snippets.

## Success Criteria

- Server always starts with dual ports: API on `PORT`, probes on `PROBE_PORT`.
- API port serves HTTPS when cert/key env vars point to valid PEM files; HTTP when unset.
- Probe port always serves plain HTTP regardless of TLS config.
- `/spark-submit` is only reachable on the API port; `/health` and `/metrics` only on the probe port.
- Server requires client certs when `TLS_CA_CERT_PATH` is set; rejects clients without valid cert.
- Cert reload happens within one poll interval after kubelet updates the mounted files (server cert, key, and CA cert).
- No dropped connections during reload — in-flight requests complete on old cert.
- Clear error messages on misconfiguration (missing file, bad PEM, key mismatch).

## Future Considerations (not in this PR)

- Configurable TLS protocol versions / cipher suites (default to JVM's TLS 1.2+ for now).
- Metrics for reload events (cert_reload_total, cert_expiry_seconds gauge).
- Per-client CN authorization (allow/deny list based on client certificate subject).
