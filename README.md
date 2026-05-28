# Kubernetes Spark Submitter Service

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

## Architecture

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         k8s-spark-submitter                              │
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
1. POST /spark-submit

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

All endpoints served on a single port (default `8080`).

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/spark-submit` | Submit a Spark job |
| POST | `/spark-submit?dryRun=true` | Validate without creating K8s resources |
| GET | `/health` | Liveness/readiness probe |
| GET | `/metrics` | Prometheus metrics (text format) |

## Metrics

**Endpoint:** `GET /metrics` (Prometheus text format)

| Metric | Type | Description |
|--------|------|-------------|
| `spark_submit_request_success_count` | Counter | Successful job submissions (HTTP 2xx) |
| `spark_submit_request_failure_count` | Counter | Failed requests, tagged by `failure_type` (HTTP status) |
| `spark_submit_requests_in_flight` | Gauge | Requests currently being processed |
| `spark_submit_request_latency_seconds` | Histogram | Request latency with configurable buckets |

## Request Format

### Basic Request

```json
{
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

### With Pod Templates

Pod templates are nested JSON objects (no escaping needed):

```json
{
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
        "name": "spark",
        "resources": { "requests": { "memory": "2Gi" } }
      }]
    }
  },
  "executor_pod_template": {
    "spec": {
      "containers": [{
        "name": "spark",
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
  "app_name": "my-spark-job",
  "message": "Spark driver pod created successfully",
  "submitted_at": "2026-02-04T22:44:02.123Z",
  "spark_app_id": "spark-b992db7da52c42298736dcbb3c9142be",
  "driver_pod_name": "my-spark-job-cff6459c2aa9538c-driver",
  "driver_pod_uid": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "namespace": "spark-jobs"
}
```

### Dry-Run Success (200 OK)

Same response format. No K8s resources are created — validates RBAC, schema, quotas via server-side dry-run.

### Error Responses

| Status | Error Code | When |
|--------|-----------|------|
| 400 | `BAD_REQUEST` | Malformed JSON or invalid spark-submit args |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | Content-Type is not `application/json` |
| 422 | `SUBMISSION_FAILED` | Valid args but K8s rejected (RBAC, quota, etc.) |
| 503 | `SERVICE_UNAVAILABLE` | Transient K8s error (retryable: 401, 429, 5xx) |

## Configuration

All configuration via environment variables:

| Variable | Default | Description |
|----------|---------|-------------|
| `PORT` | `8080` | Server port |
| `SERVER_ADDRESS` | `0.0.0.0` | Bind address |
| `SERVER_MAX_THREADS` | `200` | Max Jetty threads |
| `SHUTDOWN_TIMEOUT_MS` | `30000` | Graceful shutdown timeout |
| `K8S_CLIENT_CONNECTION_TIMEOUT_MS` | `10000` | K8s API connection timeout |
| `K8S_CLIENT_REQUEST_TIMEOUT_MS` | `30000` | K8s API request timeout |
| `K8S_CLIENT_MAX_CONCURRENT_REQUESTS` | `200` | Max parallel K8s API calls |
| `METRICS_PERCENTILES` | `0.5,0.9,0.99` | Latency histogram percentiles |
| `METRICS_SLO_MS` | `50,100,250,500,1000,2000,5000,10000,30000` | Histogram bucket boundaries |

## Building

```bash
make help       # show all targets
make build      # compile
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
- **Build**: Maven + scala-maven-plugin

## Credits

Created by **Venkateswarlu Komirisetti**. Built under the Salesforce Spark product, sponsored by [Salesforce](https://www.salesforce.com).
