# Kubernetes Spark Submitter Service

[![Build](https://github.com/venkomirisetti/k8s-spark-submitter/actions/workflows/pr-validate.yml/badge.svg)](https://github.com/venkomirisetti/k8s-spark-submitter/actions/workflows/pr-validate.yml)
[![Release](https://github.com/venkomirisetti/k8s-spark-submitter/actions/workflows/build-and-push.yml/badge.svg)](https://github.com/venkomirisetti/k8s-spark-submitter/actions/workflows/build-and-push.yml)
[![codecov](https://codecov.io/gh/venkomirisetti/k8s-spark-submitter/graph/badge.svg)](https://codecov.io/gh/venkomirisetti/k8s-spark-submitter)

A **fire-and-forget** REST API that submits Spark jobs to Kubernetes and returns immediately after creating the driver pod. The service is entirely stateless — it does not track, monitor, or manage job lifecycle. Clients that need to observe job progress should poll the **Kubernetes API** using the `driver_pod_name` returned in the submission response.

## Overview

**k8s-spark-submitter** creates Spark driver pods directly via the Kubernetes API using Apache Spark's internal libraries. This eliminates the overhead of spawning `spark-submit` subprocesses, achieving ~200ms submission latency.

### Key Characteristics

- **Spark-Compatible Parsing**: Uses Spark's internal `SparkSubmitArguments` and `SparkSubmit.prepareSubmitEnvironment` for argument parsing
- **Custom Resource Creation**: Controls K8s resource creation (fixes Spark's ConfigMap singleton issue for multi-job JVM)
- **Fire-and-Forget**: Returns immediately after pod creation without job tracking
- **Cluster Mode Only**: Only `--deploy-mode cluster` is supported; client mode submissions are rejected at parse time with a validation error
- **Stateless**: No job history or state management
- **Automatic Cleanup**: All resources are garbage collected when driver pod is deleted
- **TLS Support**: Optional HTTPS with mTLS and automatic cert reload
- **Dual-Port Architecture**: Separate API and probe ports for clean isolation

## Architecture

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         k8s-spark-submitter                             │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  ┌─────────────┐    ┌────────────────────────┐    ┌───────────────────┐ │
│  │   REST API  │───▶│   SparkSubmitter       │───▶│  K8sSparkClient   │ │
│  │   (Jetty)   │    │      (Service)         │    │  (Spark Internal) │ │
│  └─────────────┘    └────────────────────────┘    └───────────────────┘ │
│                              │                            │             │
│                              ▼                            ▼             │
│                     ┌────────────────┐          ┌──────────────────┐    │
│                     │K8sSparkSubmit  │          │KubernetesDriver  │    │
│                     │  ArgsParser    │          │    Builder       │    │
│                     │(Spark Internal)│          │ (Spark Internal) │    │
│                     └────────────────┘          └──────────────────┘    │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
                         ┌──────────────────┐
                         │   Kubernetes     │
                         │                  │
                         │  ┌────────────┐  │
                         │  │ Driver Pod │  │
                         │  └────────────┘  │
                         │  ┌────────────┐  │
                         │  │ ConfigMap  │  │
                         │  └────────────┘  │
                         │  ┌────────────┐  │
                         │  │  Service   │  │
                         │  └────────────┘  │
                         └──────────────────┘
```

## Spark Libraries Used

The service uses Spark's internal (package-private) classes:

| Class | Package | Purpose |
|-------|---------|---------|
| `SparkSubmitArguments` | `org.apache.spark.deploy` | Parse CLI args |
| `SparkSubmit.prepareSubmitEnvironment` | `org.apache.spark.deploy` | Build SparkConf |
| `ClientArguments` | `org.apache.spark.deploy.k8s.submit` | Resolve mainClass and MainAppResource (Java/Python/R) |
| `KubernetesDriverBuilder` | `org.apache.spark.deploy.k8s.submit` | Build driver pod spec |
| `KubernetesClientUtils` | `org.apache.spark.deploy.k8s.submit` | Build ConfigMaps |

**Note:** We use Spark's parsing and spec building, but control resource creation ourselves in `K8sSparkClient` to fix Spark's ConfigMap singleton naming issue (which causes collisions when submitting multiple jobs from the same JVM).

## Submission Flow

```
1. POST /api/v1/spark-submit

2. Parse Arguments (K8sSparkSubmitArgsParser)
   ├─▶ Validate deploy mode is "cluster" (rejects client mode)
   ├─▶ SparkSubmitArguments parses CLI args (supports --properties-file)
   ├─▶ SparkSubmit.prepareSubmitEnvironment builds SparkConf
   └─▶ ClientArguments resolves mainClass and MainAppResource (Java/Python/R)

3. Prepare Submission (SparkSubmitter)
   ├─▶ Extract appName, namespace from SparkConf
   ├─▶ Generate sparkAppId and driverPodName
   └─▶ Write pod templates to temp files (if provided)

4. Create Resources (K8sSparkClient)
   ├─▶ KubernetesDriverBuilder builds driver spec
   ├─▶ Build ConfigMap with spark.properties
   ├─▶ Build driver pod with ConfigMap volume
   ├─▶ Create pre-resources (secrets, service accounts)
   ├─▶ Create driver pod
   └─▶ Create post-resources (service, ConfigMap) with owner refs

5. Return SparkSubmitResponse
```

## Owner Reference Strategy

All K8s resources are linked to the driver pod via owner references:

```
Driver Pod (owner)
    ├──▶ ConfigMap (owned)
    ├──▶ Secret (owned)
    └──▶ Service (owned)
```

When the driver pod is deleted, Kubernetes garbage collection automatically removes all owned resources.

## API

Dual-port architecture — API and probes are served on separate ports:

| Port | Method | Endpoint | Description |
|------|--------|----------|-------------|
| 8080 (API) | POST | `/api/v1/spark-submit` | Submit a Spark job |
| 8080 (API) | POST | `/api/v1/spark-submit?dryRun=true` | Validate without creating K8s resources |
| 8080 (API) | GET | `/api/v1/spark-submit` | Readiness check — validates TLS/cert chain, returns usage hint |
| 8081 (Probes) | GET | `/healthz` | K8s liveness/readiness probe |
| 8081 (Probes) | GET | `/metrics` | Prometheus metrics (text format) |

## Metrics

**Endpoint:** `GET /metrics` on probe port (8081)

| Metric | Type | Description |
|--------|------|-------------|
| `spark_submit_request_success_count` | Counter | Successful job submissions (HTTP 2xx) |
| `spark_submit_request_failure_count` | Counter | Failed requests, tagged by `failure_type` (HTTP status) |
| `spark_submit_requests_in_flight` | Gauge | Requests currently being processed |
| `spark_submit_request_latency_seconds` | Histogram | Request latency with configurable buckets |

## Request Format

### Basic Request

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

### With Pod Templates

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

## Response Format

### Success (201 Created)

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

### Duplicate Submission (200 OK)

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

### Dry-Run Success (200 OK)

Same response format. No K8s resources are created — validates RBAC, schema, quotas via server-side dry-run.

### Error Responses

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

### Server

| Variable | Default | Description |
|----------|---------|-------------|
| `PORT` | `8080` | API port (HTTP or HTTPS) |
| `PROBE_PORT` | `8081` | Probe/metrics port (always HTTP) |
| `SERVER_ADDRESS` | `0.0.0.0` | Bind address |
| `SERVER_MAX_THREADS` | `200` | Max Jetty threads |
| `SHUTDOWN_TIMEOUT_MS` | `30000` | Graceful shutdown timeout |

### TLS

| Variable | Default | Description |
|----------|---------|-------------|
| `TLS_ENABLED` | `false` | Enable HTTPS on API port |
| `TLS_CERT_PATH` | (required when TLS enabled) | Path to PEM certificate chain file |
| `TLS_KEY_PATH` | (required when TLS enabled) | Path to PEM private key file (PKCS#8) |
| `TLS_CA_CERT_PATH` | (unset) | Path to CA cert for mTLS — when set, clients must present a cert signed by this CA |
| `TLS_CERT_RELOAD_ENABLED` | `false` | Enable auto-reload of certs on file change |
| `TLS_CERT_CHECK_INTERVAL_MS` | `3600000` | Debounce interval for cert file checks (default 60 min) |
| `TLS_CERT_VERIFY_WITH_HASH` | `false` | Also detect file changes via SHA-256 hash (for K8s symlink swap edge cases) |

### Kubernetes Client

| Variable | Default | Description |
|----------|---------|-------------|
| `K8S_CLIENT_CONNECTION_TIMEOUT_MS` | `10000` | K8s API connection timeout |
| `K8S_CLIENT_REQUEST_TIMEOUT_MS` | `30000` | K8s API request timeout |
| `K8S_CLIENT_MAX_CONCURRENT_REQUESTS` | `200` | Max parallel K8s API calls |

### Metrics

| Variable | Default | Description |
|----------|---------|-------------|
| `METRICS_PERCENTILES` | `0.5,0.9,0.99` | Latency histogram percentiles |
| `METRICS_SLO_MS` | `50,100,250,...,30000` | Histogram bucket boundaries |

## TLS Examples

### HTTPS only (K8s Secret)

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

### HTTPS + mTLS

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

### With cert auto-reload

```yaml
env:
  - name: TLS_CERT_RELOAD_ENABLED
    value: "true"
  - name: TLS_CERT_CHECK_INTERVAL_MS
    value: "3600000"  # check every 60 min
```

### K8s probes (always use probe port)

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

## Helm Chart

A standalone Helm chart is provided in `charts/spark-submitter/`.

### Install

```bash
helm install spark-submitter charts/spark-submitter/ \
  --namespace spark-submitter --create-namespace
```

### Key Values

| Value | Default | Description |
|-------|---------|-------------|
| `image.tag` | Chart `appVersion` | Image tag |
| `replicas` | `1` | Number of replicas |
| `port` | `8080` | API port |
| `probePort` | `8081` | Health/metrics port |
| `tls.enabled` | `false` | Enable HTTPS on API port |
| `tls.certPath` | `""` | Path to TLS certificate |
| `tls.keyPath` | `""` | Path to TLS private key |
| `tls.caCertPath` | `""` | Path to CA cert (enables mTLS) |
| `jobNamespaces` | `[]` | Namespaces for driver pods (empty = cluster-wide RBAC) |
| `rbac.create` | `true` | Create RBAC resources |
| `serviceAccount.create` | `true` | Create ServiceAccount |
| `podDisruptionBudget.enable` | `false` | Create PDB (requires replicas > 1) |

### TLS with Helm

```bash
# HTTPS only
helm install spark-submitter charts/spark-submitter/ \
  --set tls.enabled=true \
  --set tls.certPath=/etc/tls/tls.crt \
  --set tls.keyPath=/etc/tls/tls.key \
  --set 'volumes[0].name=tls-certs' \
  --set 'volumes[0].secret.secretName=my-tls-secret' \
  --set 'volumeMounts[0].name=tls-certs' \
  --set 'volumeMounts[0].mountPath=/etc/tls' \
  --set 'volumeMounts[0].readOnly=true'

# HTTPS + mTLS (add caCertPath)
helm install spark-submitter charts/spark-submitter/ \
  --set tls.enabled=true \
  --set tls.certPath=/etc/tls/tls.crt \
  --set tls.keyPath=/etc/tls/tls.key \
  --set tls.caCertPath=/etc/tls/ca.crt \
  --set 'volumes[0].name=tls-certs' \
  --set 'volumes[0].secret.secretName=my-tls-secret' \
  --set 'volumeMounts[0].name=tls-certs' \
  --set 'volumeMounts[0].mountPath=/etc/tls' \
  --set 'volumeMounts[0].readOnly=true'
```

### Namespace-Scoped RBAC

```bash
helm install spark-submitter charts/spark-submitter/ \
  --set 'jobNamespaces={spark-jobs,spark-staging}'
```

See [`charts/spark-submitter/values.yaml`](charts/spark-submitter/values.yaml) for the full reference.

## Building

```bash
make help       # show all targets
make build      # compile (uses ./mvnw)
make test       # run tests
make package    # build JAR (skip tests)
make image      # build JAR + Docker image
```

## Docker

```bash
# Build with default Spark 4.0.1 base image
make image

# Build with custom base image
make image SPARK_IMAGE=my-registry/spark:4.0.1
```

The service JAR is a thin layer (~1MB) on top of the Spark base image. All Spark/K8s/Jackson dependencies are provided by the base image at runtime.

## Tech Stack

- **Language**: Scala 2.13 on JDK 17
- **Spark**: 4.0.1 (open-source)
- **HTTP Server**: Jetty (shaded inside spark-core)
- **K8s Client**: Fabric8 7.1.0
- **Metrics**: Micrometer Prometheus
- **Build**: Maven (via `./mvnw` wrapper)

## CI/CD

- **PR Validation**: Tests run automatically on every pull request
- **Docker Push**: Image pushed to Docker Hub on merge to main (`venkomirisetti/k8s-spark-submitter:<spark-version>-<build>`)

## Credits

Created by **Venkateswarlu Komirisetti**. Built under the Salesforce Spark product, sponsored by [Salesforce](https://www.salesforce.com).
