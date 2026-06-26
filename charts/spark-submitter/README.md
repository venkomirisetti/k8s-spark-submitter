# spark-submitter

![Version: 0.1.0](https://img.shields.io/badge/Version-0.1.0-informational?style=flat-square) ![Type: application](https://img.shields.io/badge/Type-application-informational?style=flat-square) ![AppVersion: 4.0.1-latest](https://img.shields.io/badge/AppVersion-4.0.1--latest-informational?style=flat-square)

Helm chart for the Kubernetes Spark Submitter service

## Prerequisites

- Kubernetes 1.26+
- Helm 3.x

## Usage

### Install the chart

```shell
helm install [RELEASE_NAME] charts/spark-submitter/
```

For example, to create a release named `spark-submitter` in the `spark-submitter` namespace:

```shell
helm install spark-submitter charts/spark-submitter/ \
    --namespace spark-submitter \
    --create-namespace
```

See [helm install](https://helm.sh/docs/helm/helm_install) for command documentation.

### Upgrade the chart

```shell
helm upgrade [RELEASE_NAME] charts/spark-submitter/ [flags]
```

See [helm upgrade](https://helm.sh/docs/helm/helm_upgrade) for command documentation.

### Uninstall the chart

```shell
helm uninstall [RELEASE_NAME]
```

See [helm uninstall](https://helm.sh/docs/helm/helm_uninstall) for command documentation.

## TLS

The chart passes cert file paths as environment variables. You are responsible for mounting the cert files via `volumes` and `volumeMounts` values.

### HTTPS only

```shell
helm install spark-submitter charts/spark-submitter/ \
    --set tls.enabled=true \
    --set tls.certPath=/etc/tls/tls.crt \
    --set tls.keyPath=/etc/tls/tls.key \
    --set 'volumes[0].name=tls-certs' \
    --set 'volumes[0].secret.secretName=my-tls-secret' \
    --set 'volumeMounts[0].name=tls-certs' \
    --set 'volumeMounts[0].mountPath=/etc/tls' \
    --set 'volumeMounts[0].readOnly=true'
```

### HTTPS + mTLS

Add `tls.caCertPath` to enable mutual TLS — clients must present a cert signed by this CA:

```shell
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

## RBAC

By default, the chart creates a ClusterRole granting access to pods, configmaps, and services in all namespaces.

To restrict to specific namespaces, set `jobNamespaces`:

```shell
helm install spark-submitter charts/spark-submitter/ \
    --set 'jobNamespaces={spark-jobs,spark-staging}'
```

This creates namespace-scoped Roles and RoleBindings instead of cluster-wide resources.

## Local Development (kind)

```shell
make image SPARK_VERSION=4.0.1 BUILD_NUMBER=latest
kind load docker-image venkomirisetti/k8s-spark-submitter:4.0.1-latest --name submitter

helm install spark-submitter charts/spark-submitter/ \
    --kube-context kind-submitter \
    --namespace spark-submitter --create-namespace \
    --set image.pullPolicy=Never
```

## Testing

Helm unit tests are in `tests/`. Run with:

```shell
helm unittest charts/spark-submitter/
```

## Values

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| affinity | object | `{}` | Affinity for submitter pods. |
| annotations | object | `{}` | Extra annotations for submitter pods. |
| env | list | `[]` | Environment variables for the submitter container. |
| envFrom | list | `[]` | Environment variable sources for the submitter container. |
| hostUsers | string | `nil` | Whether to use the host user namespace. |
| image.pullPolicy | string | `"IfNotPresent"` | Submitter service image pull policy. |
| image.pullSecrets | list | `[]` | Image pull secrets for private image registry. |
| image.registry | string | `"docker.io"` | Submitter service image registry. |
| image.repository | string | `"venkomirisetti/k8s-spark-submitter"` | Submitter service image repository. |
| image.tag | string | `""` | Submitter service image tag (defaults to Chart appVersion). |
| jobNamespaces | list | `[]` | Namespaces where the submitter creates driver pods. If empty or contains "", cluster-wide RBAC is used. If specific namespaces are listed, namespace-scoped Roles are created. |
| labels | object | `{}` | Extra labels for submitter pods. |
| nodeSelector | object | `{}` | Node selector for submitter pods. |
| podDisruptionBudget.enable | bool | `false` | Specifies whether to create pod disruption budget for submitter. Requires replicas > 1. |
| podDisruptionBudget.minAvailable | int | `1` | Minimum number of available submitter pods. |
| podSecurityContext | object | `{"fsGroup":185}` | Security context for submitter pods. |
| port | int | `8080` | Port on which the submitter service listens. |
| priorityClassName | string | `""` | Priority class for submitter pods. |
| probePort | int | `8081` | Port for health/metrics probes (always plain HTTP). |
| prometheus.metrics.enable | bool | `true` | Enable Prometheus scrape annotations on submitter pods. |
| rbac.annotations | object | `{}` | Extra annotations for the submitter RBAC resources. |
| rbac.create | bool | `true` | Specifies whether to create RBAC resources for the submitter. |
| replicas | int | `1` | Number of replicas of the submitter service. |
| resources | object | `{}` | Resource requests and limits for the submitter container. |
| revisionHistoryLimit | int | `10` | Number of old history to retain to allow rollback. |
| securityContext | object | `{"allowPrivilegeEscalation":false,"capabilities":{"drop":["ALL"]},"privileged":false,"readOnlyRootFilesystem":true,"runAsNonRoot":true,"runAsUser":185,"seccompProfile":{"type":"RuntimeDefault"}}` | Security context for the submitter container. |
| serviceAccount.annotations | object | `{}` | Extra annotations for the submitter service account. |
| serviceAccount.automountServiceAccountToken | bool | `true` | Auto-mount service account token to the submitter pods. |
| serviceAccount.create | bool | `true` | Specifies whether to create a service account for the submitter. |
| serviceAccount.name | string | `""` | Optional name for the submitter service account. |
| sidecars | list | `[]` | Optional sidecars for the submitter pods. |
| submitPath | string | `"/api/v1/spark-submit"` | Path for the submit endpoint on the submitter service. |
| tls.caCertPath | string | `""` | Path to the CA certificate file. |
| tls.certPath | string | `""` | Path to the TLS certificate file. |
| tls.enabled | bool | `false` | Enable TLS for submitter service. |
| tls.keyPath | string | `""` | Path to the TLS private key file. |
| tolerations | list | `[]` | List of node taints to tolerate for submitter pods. |
| topologySpreadConstraints | list | `[]` | Topology spread constraints for submitter pods. |
| volumeMounts | list | `[]` | Volume mounts for the submitter container. |
| volumes | list | `[]` | Volumes for submitter pods. |
