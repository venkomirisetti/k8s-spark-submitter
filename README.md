# Kubernetes Spark Submitter Service

A **fire-and-forget** REST API that submits Spark jobs to Kubernetes and returns immediately after creating the driver pod. The service is entirely stateless — it does not track, monitor, or manage job lifecycle. Clients that need to observe job progress should poll the **Kubernetes API** using the `driver_pod_name` returned in the submission response.

## Overview

**k8s-spark-submitter** creates Spark driver pods directly via the Kubernetes API using Apache Spark's internal libraries. This eliminates the overhead of spawning `spark-submit` subprocesses, achieving ~200ms submission latency.

### Key Characteristics

- **Spark-Compatible Parsing**: Uses Spark's internal `SparkSubmitArguments` and `SparkSubmit.prepareSubmitEnvironment` for argument parsing
- **Custom Resource Creation**: Controls K8s resource creation (fixes Spark's ConfigMap singleton issue for multi-job JVM)
- **Fire-and-Forget**: Returns immediately after pod creation without job tracking
- **Cluster Mode Only**: Drivers always run as separate pods on the cluster
- **Stateless**: No job history or state management
- **Automatic Cleanup**: All resources are garbage collected when driver pod is deleted

## Architecture

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         k8s-spark-submitter                              │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  ┌─────────────┐    ┌────────────────────────┐    ┌───────────────────┐ │
│  │   REST API  │───▶│   SparkSubmitter       │───▶│   SfSparkClient   │ │
│  │  (Spring)   │    │      (Service)         │    │  (Spark Internal) │ │
│  └─────────────┘    └────────────────────────┘    └───────────────────┘ │
│                              │                            │             │
│                              ▼                            ▼             │
│                     ┌────────────────┐          ┌──────────────────┐    │
│                     │SfSparkSubmit   │          │KubernetesDriver  │    │
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
| `KubernetesDriverBuilder` | `org.apache.spark.deploy.k8s.submit` | Build driver pod spec |
| `KubernetesClientUtils` | `org.apache.spark.deploy.k8s.submit` | Build ConfigMaps |

**Note:** We use Spark's parsing and spec building, but control resource creation ourselves in `SfSparkClient` to fix Spark's ConfigMap singleton naming issue (which causes collisions when submitting multiple jobs from the same JVM).

## Submission Flow

```
1. POST /api/v1/spark/submit

2. Parse Arguments (SfSparkSubmitArgsParser)
   ├─▶ SparkSubmitArguments parses CLI args
   └─▶ SparkSubmit.prepareSubmitEnvironment builds SparkConf

3. Prepare Submission (SparkSubmitter)
   ├─▶ Extract appName, namespace from SparkConf
   ├─▶ Generate sparkAppId and driverPodName
   └─▶ Write pod templates to temp files (if provided)

4. Create Resources (SfSparkClient)
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

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/spark/submit` | Submit a Spark job |
| POST | `/api/v1/spark/selftest` | Sidecar startup probe. Runs the submit path with Kubernetes server-side dry-run. Returns 200 on success, 503 when disabled, 422 on submission failure. No request body. |

**Management endpoints** (port 15372):

| Endpoint | Description |
|----------|-------------|
| `/manage/health` | Aggregate health (all components) |
| `/manage/health/liveness` | Liveness probe |
| `/manage/health/readiness` | Readiness probe |
| `/manage/prometheus` | Prometheus metrics (text format) |

## Metrics

**Endpoint:** `http://<host>:15372/manage/prometheus` (Prometheus text format)

**Flow:** Sidecar scrapes `/manage/prometheus` → Argus (Monitoring Cloud)

The service emits standard platform metrics (JVM, process, Jetty) and the following custom metrics:

| Metric | Type | Description |
|--------|------|-------------|
| `spark_submit_request_success_count` | Counter | Successful job submissions (HTTP 2xx) |
| `spark_submit_request_failure_count` | Counter | Failed requests, tagged by `failure_type` (HTTP status) |
| `spark_submit_requests_in_flight` | Gauge | Requests currently being processed |
| `spark_submit_request_latency_seconds` | Histogram | Request latency with configurable buckets |
| `spark_submit_request_latency_seconds_count` | Counter | Total request count (auto-generated) |

**Configuration:**
- Histogram buckets configurable via `application.yml` (default: 50ms, 100ms, 250ms, 500ms, 1s, 2s, 5s, 10s, 30s)
- Can be customized per environment using profile-specific YAML files
- Requires `metrics.enableMicrometerMetrics: true` in `application.yml`

## Request Format

### Basic Request

```json
{
  "spark_submit_args": [
    "--master", "k8s://https://kubernetes.default.svc:443",
    "--deploy-mode", "cluster",
    "--name", "my-spark-job",
    "--conf", "spark.kubernetes.namespace=spark-jobs",
    "--conf", "spark.kubernetes.container.image=spark:3.5.5",
    "--conf", "spark.kubernetes.authenticate.driver.serviceAccountName=spark",
    "--class", "com.example.SparkApp",
    "--conf", "spark.executor.instances=3",
    "s3://bucket/app.jar",
    "--input", "s3://input"
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
    "--conf", "spark.kubernetes.container.image=spark:3.5.5",
    "--class", "com.example.SparkApp",
    "s3://bucket/app.jar"
  ],
  "driver_pod_template": {
    "apiVersion": "v1",
    "kind": "Pod",
    "spec": {
      "containers": [{
        "name": "spark",
        "resources": { "requests": { "memory": "2Gi" } }
      }]
    }
  },
  "executor_pod_template": {
    "apiVersion": "v1",
    "kind": "Pod",
    "spec": {
      "containers": [{
        "name": "spark",
        "resources": { "requests": { "memory": "4Gi" } }
      }]
    }
  }
}
```

### Optional Arguments

| Argument | Default | Description |
|----------|---------|-------------|
| `--name` | Main class or JAR/Python filename | Application name (optional, auto-generated if not provided) |
| `--conf spark.kubernetes.namespace` | `default` | Target namespace |

**App Name Defaulting Behavior**:
- If `--name` is not provided, Spark automatically uses:
  - **Java/Scala**: Main class name (e.g., `org.apache.spark.examples.SparkPi`)
  - **Python**: Python file name (e.g., `pi.py`)
- The app name is sanitized for Kubernetes pod naming (lowercase, special chars replaced)

### Required Arguments

| Argument | Description |
|----------|-------------|
| `--conf spark.kubernetes.container.image` | Spark container image for driver and executors |

## Response Format

### Success Response (201 Created)

```json
{
  "app_name": "my-spark-job",
  "message": "Spark driver pod created successfully",
  "submitted_at": "2026-02-04T22:44:02.123456789Z",
  "spark_app_id": "spark-b992db7da52c42298736dcbb3c9142be",
  "driver_pod_name": "my-spark-job-cff6459c2aa9538c-driver",
  "namespace": "spark-jobs"
}
```

**Response Fields**:
- `app_name`: Spark application name (from `--name` or auto-generated)
- `message`: Success message
- `submitted_at`: ISO 8601 timestamp with nanosecond precision
- `spark_app_id`: Unique Spark application ID (format: `spark-{UUID}`)
- `driver_pod_name`: Kubernetes driver pod name
- `namespace`: Kubernetes namespace where resources were created

### Error Responses

#### Validation Error (400 Bad Request)
Invalid request format or Spark arguments:
```json
{
  "error": "BAD_REQUEST",
  "message": "Malformed request body",
  "details": null,
  "timestamp": "2026-02-04T22:44:01Z"
}
```

#### Submission Error (422 Unprocessable Entity)
Valid request, but Kubernetes submission failed:
```json
{
  "error": "SUBMISSION_FAILED",
  "message": "Failed to submit: Forbidden",
  "details": "pods is forbidden: User \"system:serviceaccount:default:spark\" cannot create resource \"pods\" in namespace \"spark-jobs\"",
  "timestamp": "2026-02-04T22:44:01Z"
}
```

**Note**: The service performs minimal validation - most argument validation is delegated to Spark's internal parser. Kubernetes API errors (permissions, resource quotas, etc.) are returned as submission errors.

## Building

```bash
mvn clean package
```
