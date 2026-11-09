# User Guide

## Getting started

Install the chart, submit SparkPi, and watch the driver pod. The examples use a `spark-jobs` namespace for
both the submitter and the jobs.

**1. Install the submitter.** The chart runs the Spark 4.0.1 image by default; add
`--set image.tag=3.5.9-latest` for Spark 3.5. The container has a read-only root filesystem, so mount an
`emptyDir` at `/tmp` for Spark's temporary files.

```bash
cat > values-tmp.yaml <<EOF
volumes: [{name: tmp, emptyDir: {}}]
volumeMounts: [{name: tmp, mountPath: /tmp}]
EOF

helm install spark-submitter charts/spark-submitter -n spark-jobs --create-namespace \
  -f values-tmp.yaml --set "jobNamespaces[0]=spark-jobs"
kubectl -n spark-jobs rollout status deploy/spark-submitter
```

**2. Let drivers create executors.** Driver pods run as the namespace's `default` service account unless
you set `spark.kubernetes.authenticate.driver.serviceAccountName`.

```bash
kubectl -n spark-jobs create rolebinding spark-driver --clusterrole=edit --serviceaccount=spark-jobs:default
```

**3. Submit SparkPi.**

```bash
kubectl -n spark-jobs port-forward deploy/spark-submitter 8080:8080 8081:8081 &

curl -s -X POST localhost:8080/api/v1/spark-submit -H 'Content-Type: application/json' -d '{
  "spark_submit_args": [
    "--master", "k8s://https://kubernetes.default.svc",
    "--deploy-mode", "cluster",
    "--name", "spark-pi",
    "--class", "org.apache.spark.examples.SparkPi",
    "--conf", "spark.kubernetes.namespace=spark-jobs",
    "--conf", "spark.kubernetes.container.image=docker.io/library/spark:4.0.1",
    "--conf", "spark.executor.instances=1",
    "local:///opt/spark/examples/jars/spark-examples.jar", "100"
  ]
}'
```

For Spark 3.5, use the `docker.io/library/spark:3.5.9-java17` image and
`spark-examples_2.12-3.5.9.jar`. A `201` response includes the `driver_pod_name`.

**4. Watch the job.** The submitter doesn't track jobs; use the Kubernetes API.

```bash
kubectl -n spark-jobs get pod <driver_pod_name> -w
kubectl -n spark-jobs logs <driver_pod_name> | grep "Pi is roughly"
```

Health and metrics are on the probe port: `curl localhost:8081/healthz` and `curl localhost:8081/metrics`.

## API

Dual-port architecture — API and probes are served on separate ports:

| Port | Method | Endpoint | Description |
|------|--------|----------|-------------|
| 8080 (API) | POST | `/api/v1/spark-submit` | Submit a Spark job |
| 8080 (API) | POST | `/api/v1/spark-submit?dryRun=true` | Validate without creating K8s resources |
| 8080 (API) | GET | `/api/v1/spark-submit` | Readiness check — validates TLS/cert chain, returns usage hint |
| 8081 (Probes) | GET | `/healthz` | K8s liveness/readiness probe |
| 8081 (Probes) | GET | `/metrics` | Prometheus metrics (text format) |

### Metrics

**Endpoint:** `GET /metrics` on probe port (8081)

| Metric | Type | Description |
|--------|------|-------------|
| `spark_submit_request_success_count` | Counter | Successful job submissions (HTTP 2xx) |
| `spark_submit_request_failure_count` | Counter | Failed requests, tagged by `failure_type` (HTTP status) |
| `spark_submit_requests_in_flight` | Gauge | Requests currently being processed |
| `spark_submit_request_latency_seconds` | Histogram | Request latency with configurable buckets |

### Request Format

#### Basic Request

| Field | Required | Description |
|-------|----------|-------------|
| `submission_id` | No | Correlation ID for log filtering. Auto-generated UUID if omitted (`G-<uuid>`) |
| `spark_submit_args` | Yes | Array of spark-submit CLI arguments |
| `driver_pod_template` | No | Driver pod template as nested JSON object |
| `executor_pod_template` | No | Executor pod template as nested JSON object |

```json
{
  "submission_id": "my-correlation-id-123",
  "spark_submit_args": [
    "--master", "k8s://https://kubernetes.default.svc:443",
    "--deploy-mode", "cluster",
    "--name", "my-spark-job",
    "--conf", "spark.kubernetes.namespace=spark-jobs",
    "--conf", "spark.kubernetes.container.image=spark:4.0.1",
    "--conf", "spark.kubernetes.authenticate.driver.serviceAccountName=spark",
    "--class", "com.example.SparkApp",
    "local:///opt/spark/app.jar"
  ]
}
```

> **Note:** `submission_id` is optional. If omitted, a UUID is auto-generated (e.g., `G-550e8400-e29b-41d4-a716-446655440000`). It is included in all server-side log lines for the request, enabling easy log filtering for debugging.

#### With Pod Templates

Pod templates are nested JSON objects (no escaping needed):

```json
{
  "submission_id": "batch-run-456",
  "spark_submit_args": [
    "--master", "k8s://https://kubernetes.default.svc:443",
    "--deploy-mode", "cluster",
    "--name", "my-spark-job",
    "--conf", "spark.kubernetes.namespace=spark-jobs",
    "--conf", "spark.kubernetes.container.image=spark:4.0.1",
    "--class", "com.example.SparkApp",
    "local:///opt/spark/app.jar"
  ],
  "driver_pod_template": {
    "spec": {
      "containers": [{
        "name": "spark-kubernetes-driver",
        "resources": { "requests": { "memory": "2Gi" } }
      }]
    }
  },
  "executor_pod_template": {
    "spec": {
      "containers": [{
        "name": "spark-kubernetes-executor",
        "resources": { "requests": { "memory": "4Gi" } }
      }]
    }
  }
}
```

### Response Format

#### Success (201 Created)

```json
{
  "submission_id": "my-correlation-id-123",
  "app_name": "my-spark-job",
  "message": "Spark driver pod created successfully",
  "submitted_at": "2026-02-04T22:44:02.123Z",
  "spark_app_id": "spark-b992db7da52c42298736dcbb3c9142be",
  "driver_pod_name": "my-spark-job-cff6459c2aa9538c-driver",
  "driver_pod_uid": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "namespace": "spark-jobs",
  "duplicate_submission": false
}
```

#### Duplicate Submission (200 OK)

When the same `submission_id` is submitted again and the driver pod already exists, the service returns the existing pod details without creating a new one.

```json
{
  "submission_id": "my-correlation-id-123",
  "app_name": "my-spark-job",
  "message": "Driver pod already exists for this submission",
  "submitted_at": "2026-02-04T22:44:05.456Z",
  "spark_app_id": "spark-b992db7da52c42298736dcbb3c9142be",
  "driver_pod_name": "my-spark-job-cff6459c2aa9538c-driver",
  "driver_pod_uid": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "namespace": "spark-jobs",
  "duplicate_submission": true
}
```

#### Dry-Run Success (200 OK)

Same response format. No K8s resources are created — validates RBAC, schema, quotas via server-side dry-run.

#### Error Responses

| Status | Error Code | When |
|--------|-----------|------|
| 400 | `BAD_REQUEST` | Malformed JSON body |
| 400 | `INVALID_SPARK_SUBMIT_ARGS` | Invalid spark-submit arguments |
| 405 | `METHOD_NOT_ALLOWED` | Non-POST request to /spark-submit |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | Content-Type is not application/json |
| 409 | `DRIVER_POD_ALREADY_EXISTS` | Pod name conflict with a different submission |
| 422 | `INVALID_POD_TEMPLATE` | K8s rejected pod template (K8s 422) |
| 500 | `INTERNAL_SERVER_ERROR` | Unexpected failures (K8s 401, 403, 5xx) |
| 503 | `SUBMITTER_OVERLOADED` | Transient errors (K8s 429, network timeouts) |

## Configuration

All configuration via environment variables:

#### Server

| Variable | Default | Description |
|----------|---------|-------------|
| `PORT` | `8080` | API port (HTTP or HTTPS) |
| `PROBE_PORT` | `8081` | Probe/metrics port (always HTTP) |
| `SERVER_ADDRESS` | `0.0.0.0` | Bind address |
| `SERVER_MAX_THREADS` | `200` | Max Jetty threads |
| `SHUTDOWN_TIMEOUT_MS` | `30000` | Graceful shutdown timeout |

#### TLS

| Variable | Default | Description |
|----------|---------|-------------|
| `TLS_ENABLED` | `false` | Enable HTTPS on API port |
| `TLS_CERT_PATH` | (required when TLS enabled) | Path to PEM certificate chain file |
| `TLS_KEY_PATH` | (required when TLS enabled) | Path to PEM private key file (PKCS#8) |
| `TLS_CA_CERT_PATH` | (unset) | Path to CA cert for mTLS — when set, clients must present a cert signed by this CA |
| `TLS_CERT_RELOAD_ENABLED` | `false` | Enable auto-reload of certs on file change |
| `TLS_CERT_CHECK_INTERVAL_MS` | `3600000` | Debounce interval for cert file checks (default 60 min) |
| `TLS_CERT_VERIFY_WITH_HASH` | `false` | Also detect file changes via SHA-256 hash (for K8s symlink swap edge cases) |

#### Kubernetes Client

| Variable | Default | Description |
|----------|---------|-------------|
| `K8S_CLIENT_CONNECTION_TIMEOUT_MS` | `10000` | K8s API connection timeout |
| `K8S_CLIENT_REQUEST_TIMEOUT_MS` | `30000` | K8s API request timeout |
| `K8S_CLIENT_MAX_CONCURRENT_REQUESTS` | `200` | Max parallel K8s API calls |

#### Metrics

| Variable | Default | Description |
|----------|---------|-------------|
| `METRICS_PERCENTILES` | `0.5,0.9,0.99` | Latency histogram percentiles |
| `METRICS_SLO_MS` | `50,100,250,...,30000` | Histogram bucket boundaries |

### TLS Examples

#### HTTPS only (K8s Secret)

```yaml
env:
  - name: TLS_ENABLED
    value: "true"
  - name: TLS_CERT_PATH
    value: "/etc/tls/tls.crt"
  - name: TLS_KEY_PATH
    value: "/etc/tls/tls.key"
volumeMounts:
  - name: tls-cert
    mountPath: /etc/tls
    readOnly: true
volumes:
  - name: tls-cert
    secret:
      secretName: spark-submitter-tls
```

#### HTTPS + mTLS

```yaml
env:
  - name: TLS_ENABLED
    value: "true"
  - name: TLS_CERT_PATH
    value: "/etc/tls/tls.crt"
  - name: TLS_KEY_PATH
    value: "/etc/tls/tls.key"
  - name: TLS_CA_CERT_PATH
    value: "/etc/tls-ca/ca.crt"
```

#### With cert auto-reload

```yaml
env:
  - name: TLS_CERT_RELOAD_ENABLED
    value: "true"
  - name: TLS_CERT_CHECK_INTERVAL_MS
    value: "3600000"  # check every 60 min
```

#### K8s probes (always use probe port)

```yaml
ports:
  - name: api
    containerPort: 8080
  - name: probes
    containerPort: 8081
livenessProbe:
  httpGet:
    path: /healthz
    port: probes
readinessProbe:
  httpGet:
    path: /healthz
    port: probes
```
