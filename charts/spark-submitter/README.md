# spark-submitter Helm Chart

Helm chart for deploying the [k8s-spark-submitter](../../README.md) service on Kubernetes.

## Prerequisites

- Kubernetes 1.26+
- Helm 3.x

## Install

```bash
helm install spark-submitter charts/spark-submitter/ \
  --namespace spark-submitter --create-namespace
```

## Upgrade

```bash
helm upgrade spark-submitter charts/spark-submitter/ \
  --namespace spark-submitter
```

## Uninstall

```bash
helm uninstall spark-submitter -n spark-submitter
```

## Values

### Image

| Value | Default | Description |
|-------|---------|-------------|
| `image.registry` | `docker.io` | Image registry |
| `image.repository` | `venkomirisetti/k8s-spark-submitter` | Image repository |
| `image.tag` | `""` (uses Chart `appVersion`) | Image tag |
| `image.pullPolicy` | `IfNotPresent` | Pull policy |
| `image.pullSecrets` | `[]` | Pull secrets for private registries |

### Service

| Value | Default | Description |
|-------|---------|-------------|
| `replicas` | `1` | Number of replicas |
| `revisionHistoryLimit` | `10` | Rollback history to retain |
| `port` | `8080` | API port (HTTP or HTTPS) |
| `probePort` | `8081` | Health/metrics port (always HTTP) |
| `submitPath` | `/api/v1/spark-submit` | Submit endpoint path |

### TLS

The chart passes cert file paths as environment variables. You are responsible for mounting the cert files via `volumes` and `volumeMounts`.

| Value | Default | Description |
|-------|---------|-------------|
| `tls.enabled` | `false` | Enable HTTPS on the API port |
| `tls.certPath` | `""` | Path to the TLS certificate file |
| `tls.keyPath` | `""` | Path to the TLS private key file |
| `tls.caCertPath` | `""` | Path to CA cert for mTLS (omit for server-only TLS) |

### RBAC

| Value | Default | Description |
|-------|---------|-------------|
| `rbac.create` | `true` | Create RBAC resources |
| `rbac.annotations` | `{}` | Extra annotations for RBAC resources |
| `jobNamespaces` | `[]` | Namespaces for driver pods. Empty = cluster-wide ClusterRole. Specific namespaces = scoped Roles per namespace |

### Service Account

| Value | Default | Description |
|-------|---------|-------------|
| `serviceAccount.create` | `true` | Create a ServiceAccount |
| `serviceAccount.name` | `""` | Custom name (defaults to release fullname) |
| `serviceAccount.annotations` | `{}` | Extra annotations |
| `serviceAccount.automountServiceAccountToken` | `true` | Mount token into pods |

### Pod Configuration

| Value | Default | Description |
|-------|---------|-------------|
| `labels` | `{}` | Extra pod labels |
| `annotations` | `{}` | Extra pod annotations |
| `env` | `[]` | Extra environment variables |
| `envFrom` | `[]` | Environment variable sources |
| `volumeMounts` | `[]` | Extra volume mounts |
| `volumes` | `[]` | Extra volumes |
| `sidecars` | `[]` | Sidecar containers |
| `resources` | `{}` | CPU/memory requests and limits |
| `nodeSelector` | `{}` | Node selector |
| `affinity` | `{}` | Affinity rules |
| `tolerations` | `[]` | Tolerations |
| `topologySpreadConstraints` | `[]` | Topology spread (requires replicas > 1) |
| `priorityClassName` | `""` | Priority class |
| `hostUsers` | `null` | Use host user namespace (K8s 1.30+) |

### Security

| Value | Default | Description |
|-------|---------|-------------|
| `securityContext.readOnlyRootFilesystem` | `true` | Read-only root FS |
| `securityContext.runAsNonRoot` | `true` | Non-root user |
| `securityContext.runAsUser` | `185` | User ID |
| `securityContext.privileged` | `false` | No privileged mode |
| `securityContext.allowPrivilegeEscalation` | `false` | No privilege escalation |
| `securityContext.capabilities.drop` | `[ALL]` | Drop all capabilities |
| `securityContext.seccompProfile.type` | `RuntimeDefault` | Seccomp profile |
| `podSecurityContext.fsGroup` | `185` | Pod filesystem group |

### Pod Disruption Budget

| Value | Default | Description |
|-------|---------|-------------|
| `podDisruptionBudget.enable` | `false` | Create a PDB (requires replicas > 1) |
| `podDisruptionBudget.minAvailable` | `1` | Minimum available pods |

### Prometheus

| Value | Default | Description |
|-------|---------|-------------|
| `prometheus.metrics.enable` | `true` | Add Prometheus scrape annotations to pods |

## Examples

### Basic install

```bash
helm install spark-submitter charts/spark-submitter/ \
  --namespace spark-submitter --create-namespace
```

### Production (HA with TLS)

```bash
helm install spark-submitter charts/spark-submitter/ \
  --namespace spark-submitter --create-namespace \
  --set replicas=3 \
  --set podDisruptionBudget.enable=true \
  --set podDisruptionBudget.minAvailable=2 \
  --set tls.enabled=true \
  --set tls.certPath=/etc/tls/tls.crt \
  --set tls.keyPath=/etc/tls/tls.key \
  --set tls.caCertPath=/etc/tls/ca.crt \
  --set 'volumes[0].name=tls-certs' \
  --set 'volumes[0].secret.secretName=spark-submitter-tls' \
  --set 'volumeMounts[0].name=tls-certs' \
  --set 'volumeMounts[0].mountPath=/etc/tls' \
  --set 'volumeMounts[0].readOnly=true' \
  --set 'jobNamespaces={spark-prod}' \
  --set resources.requests.cpu=100m \
  --set resources.requests.memory=256Mi \
  --set resources.limits.cpu=500m \
  --set resources.limits.memory=512Mi
```

### Namespace-scoped RBAC

```bash
helm install spark-submitter charts/spark-submitter/ \
  --set 'jobNamespaces={spark-jobs,spark-staging}'
```

This creates a Role + RoleBinding in each listed namespace instead of a cluster-wide ClusterRole.

### Local development (kind)

```bash
make image SPARK_VERSION=4.0.1 BUILD_NUMBER=latest
kind load docker-image venkomirisetti/k8s-spark-submitter:4.0.1-latest --name submitter

helm install spark-submitter charts/spark-submitter/ \
  --kube-context kind-submitter \
  --namespace spark-submitter --create-namespace \
  --set image.pullPolicy=Never
```

## Testing

Helm unit tests are in `tests/`. Run with:

```bash
helm unittest charts/spark-submitter/
```
