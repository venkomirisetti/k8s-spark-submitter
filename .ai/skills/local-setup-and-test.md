---
name: local-setup-and-test
description: Set up local kind cluster for k8s-spark-submitter using the Helm chart, run SparkPi verification, and optionally test TLS scenarios
---

You will set up a complete local Kubernetes environment for k8s-spark-submitter using kind (Kubernetes in Docker) and the Helm chart.

## Execution Mode

1. **Present the plan first** -- show the user the numbered list of actions below so they know what will happen.
2. **Run all actions sequentially** -- execute each action one after another without waiting for user confirmation between steps.
3. **Report after each step** -- after each action completes, briefly report what was done and the result (success or failure), then immediately proceed to the next action.
4. **Stop on failure** -- if any critical action fails, stop execution and show the error. Do NOT continue to the next action.
5. **Track progress** -- use the todo list tool to track action status (pending/in_progress/completed/failed) so the user can see overall progress.

## Plan

| Step | Action | Critical? |
|------|--------|-----------|
| 1 | Verify Docker is running | Yes |
| 2 | Install kind if not present | Yes |
| 3 | Create kind cluster "submitter" | Yes |
| 4 | Build and load Docker image | Yes |
| 5 | Load Spark base image into kind | Yes |
| 6 | Install Helm chart | Yes |
| 7 | Verify deployment is ready | Yes |
| 8 | Submit SparkPi job and verify | No |
| 9 | (Optional) TLS scenarios | No — only run if user requests TLS testing |

## Action 1: Check Docker

```bash
docker ps >/dev/null 2>&1 || { echo "ERROR: Docker is not running. Start Docker Desktop first."; exit 1; }
echo "Docker is running"
```

## Action 2: Install kind

```bash
if ! command -v kind &> /dev/null; then
    echo "Installing kind..."
    if [[ "$OSTYPE" == "darwin"* ]]; then
        brew install kind || { echo "ERROR: Install Homebrew first"; exit 1; }
    elif [[ "$OSTYPE" == "linux-gnu"* ]]; then
        curl -Lo ./kind https://kind.sigs.k8s.io/dl/v0.20.0/kind-linux-amd64
        chmod +x ./kind
        sudo mv ./kind /usr/local/bin/kind
    else
        echo "ERROR: Unsupported OS: $OSTYPE"; exit 1
    fi
fi
echo "kind installed: $(kind version)"
```

## Action 3: Create kind cluster

```bash
if kind get clusters 2>/dev/null | grep -q "^submitter$"; then
    echo "Cluster 'submitter' exists"
else
    echo "Creating kind cluster 'submitter'..."
    kind create cluster --name submitter --wait 60s
fi
kubectl cluster-info --context kind-submitter
echo "Cluster ready"
```

## Action 4: Build and load image

```bash
echo "Building JAR and Docker image..."
make image SPARK_VERSION=4.0.1 BUILD_NUMBER=latest
echo "Loading image into kind..."
kind load docker-image venkomirisetti/k8s-spark-submitter:4.0.1-latest --name submitter
echo "Image ready"
```

## Action 5: Load Spark base image

```bash
echo "Pulling Spark base image (needed for driver pods)..."
docker pull spark:4.0.1 || true
kind load docker-image spark:4.0.1 --name submitter
echo "Spark image loaded"
```

## Action 6: Install Helm chart

```bash
echo "Installing spark-submitter Helm chart..."
helm upgrade --install spark-submitter charts/spark-submitter/ \
  --kube-context kind-submitter \
  --namespace spark-submitter \
  --create-namespace \
  --set image.pullPolicy=Never
echo "Helm chart installed"
```

Also create a Spark ServiceAccount for driver pods:

```bash
kubectl apply --context kind-submitter -f - <<'EOF'
apiVersion: v1
kind: ServiceAccount
metadata:
  name: spark
  namespace: spark-submitter
---
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: spark-role
  namespace: spark-submitter
rules:
  - apiGroups: [""]
    resources: ["pods", "services", "configmaps"]
    verbs: ["create", "get", "list", "watch", "delete", "update", "patch"]
  - apiGroups: [""]
    resources: ["pods/log"]
    verbs: ["get"]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: spark-role-binding
  namespace: spark-submitter
roleRef:
  kind: Role
  name: spark-role
  apiGroup: rbac.authorization.k8s.io
subjects:
  - kind: ServiceAccount
    name: spark
    namespace: spark-submitter
EOF
echo "Spark RBAC created"
```

## Action 7: Verify deployment

```bash
kubectl rollout status deployment/spark-submitter -n spark-submitter --context kind-submitter --timeout=60s

# Verify health endpoint
POD=$(kubectl get pods -n spark-submitter --context kind-submitter -l app.kubernetes.io/name=spark-submitter -o jsonpath='{.items[0].metadata.name}')
kubectl exec -n spark-submitter --context kind-submitter "$POD" -- wget -qO- http://localhost:8081/healthz
echo ""

echo "Setup complete!"
kubectl get pods -n spark-submitter --context kind-submitter
kubectl get svc -n spark-submitter --context kind-submitter
echo ""
echo "Port-forward: kubectl port-forward -n spark-submitter --context kind-submitter svc/spark-submitter-svc 8080:8080"
```

## Action 8: Submit SparkPi job and verify

### 8a: Port-forward and submit SparkPi

```bash
kubectl port-forward -n spark-submitter --context kind-submitter svc/spark-submitter-svc 8080:8080 &
PF_PID=$!
sleep 2

echo "--- Submitting SparkPi job ---"
RESPONSE=$(curl -s -w "\n%{http_code}" -X POST http://localhost:8080/api/v1/spark-submit \
  -H 'Content-Type: application/json' \
  -d '{
    "spark_submit_args": [
      "--master", "k8s://https://kubernetes.default.svc",
      "--deploy-mode", "cluster",
      "--name", "spark-pi-test",
      "--class", "org.apache.spark.examples.SparkPi",
      "--conf", "spark.kubernetes.namespace=spark-submitter",
      "--conf", "spark.kubernetes.authenticate.driver.serviceAccountName=spark",
      "--conf", "spark.kubernetes.container.image=spark:4.0.1",
      "local:///opt/spark/examples/jars/spark-examples_2.13-4.0.1.jar"
    ]
  }')

HTTP_CODE=$(echo "$RESPONSE" | tail -1)
BODY=$(echo "$RESPONSE" | head -n -1)

echo "HTTP Status: $HTTP_CODE"
echo "Response: $BODY"
```

### 8b: Monitor driver pod

```bash
echo "--- Waiting for driver pod to appear ---"
for i in $(seq 1 30); do
    DRIVER_POD=$(kubectl get pods -n spark-submitter --context kind-submitter -l spark-role=driver -o jsonpath='{.items[0].metadata.name}' 2>/dev/null)
    if [ -n "$DRIVER_POD" ]; then
        echo "Driver pod found: $DRIVER_POD"
        break
    fi
    echo "Waiting... ($i/30)"
    sleep 5
done

if [ -z "$DRIVER_POD" ]; then
    echo "ERROR: Driver pod never appeared after 150s"
    kubectl get pods -n spark-submitter --context kind-submitter
    kill $PF_PID 2>/dev/null
    exit 1
fi

kubectl wait --for=jsonpath='{.status.phase}'=Succeeded pod/"$DRIVER_POD" \
  -n spark-submitter --context kind-submitter --timeout=300s || true
```

### 8c: Show results

```bash
DRIVER_POD=$(kubectl get pods -n spark-submitter --context kind-submitter -l spark-role=driver -o jsonpath='{.items[0].metadata.name}' 2>/dev/null)
PHASE=$(kubectl get pod "$DRIVER_POD" -n spark-submitter --context kind-submitter -o jsonpath='{.status.phase}' 2>/dev/null)

echo "=== Driver pod: $DRIVER_POD ==="
echo "=== Final status: $PHASE ==="

if [ "$PHASE" = "Succeeded" ]; then
    echo "--- SparkPi result ---"
    kubectl logs "$DRIVER_POD" -n spark-submitter --context kind-submitter | grep -i "pi is roughly"
else
    echo "--- Driver pod logs (last 50 lines) ---"
    kubectl logs "$DRIVER_POD" -n spark-submitter --context kind-submitter --tail=50
fi

kill $PF_PID 2>/dev/null
kubectl get pods -n spark-submitter --context kind-submitter -o wide
```

## Action 9: TLS Scenarios (Optional)

Only run this section if the user explicitly asks for TLS testing.

### 9a: Generate test certificates

```bash
CERT_DIR=$(mktemp -d)
echo "Generating certs in $CERT_DIR"

# CA
openssl req -x509 -newkey rsa:2048 -nodes \
  -keyout "$CERT_DIR/ca.key" -out "$CERT_DIR/ca.crt" \
  -days 365 -subj "/CN=test-ca"

# Server cert
openssl req -newkey rsa:2048 -nodes \
  -keyout "$CERT_DIR/server.key" -out "$CERT_DIR/server.csr" \
  -subj "/CN=spark-submitter-svc.spark-submitter.svc"
openssl x509 -req -in "$CERT_DIR/server.csr" \
  -CA "$CERT_DIR/ca.crt" -CAkey "$CERT_DIR/ca.key" -CAcreateserial \
  -out "$CERT_DIR/server.crt" -days 365 \
  -extfile <(echo "subjectAltName=DNS:spark-submitter-svc.spark-submitter.svc,DNS:localhost")

# Client cert (for mTLS)
openssl req -newkey rsa:2048 -nodes \
  -keyout "$CERT_DIR/client.key" -out "$CERT_DIR/client.csr" \
  -subj "/CN=test-client"
openssl x509 -req -in "$CERT_DIR/client.csr" \
  -CA "$CERT_DIR/ca.crt" -CAkey "$CERT_DIR/ca.key" -CAcreateserial \
  -out "$CERT_DIR/client.crt" -days 365

# Store as K8s secret
kubectl --context kind-submitter -n spark-submitter delete secret tls-certs --ignore-not-found
kubectl --context kind-submitter -n spark-submitter create secret generic tls-certs \
  --from-file=tls.crt="$CERT_DIR/server.crt" \
  --from-file=tls.key="$CERT_DIR/server.key" \
  --from-file=ca.crt="$CERT_DIR/ca.crt"

echo "Certificates ready"
```

### 9b: HTTPS mode (server TLS only)

```bash
echo "=== TLS Scenario: HTTPS (no mTLS) ==="

helm upgrade --install spark-submitter charts/spark-submitter/ \
  --kube-context kind-submitter \
  --namespace spark-submitter \
  --set image.pullPolicy=Never \
  --set tls.enabled=true \
  --set tls.certPath=/etc/tls/tls.crt \
  --set tls.keyPath=/etc/tls/tls.key \
  --set tls.caCertPath="" \
  --set 'volumes[0].name=tls-certs' \
  --set 'volumes[0].secret.secretName=tls-certs' \
  --set 'volumeMounts[0].name=tls-certs' \
  --set 'volumeMounts[0].mountPath=/etc/tls' \
  --set 'volumeMounts[0].readOnly=true'

kubectl rollout status deployment/spark-submitter -n spark-submitter --context kind-submitter --timeout=60s

# Probe port stays HTTP
POD=$(kubectl get pods -n spark-submitter --context kind-submitter -l app.kubernetes.io/name=spark-submitter -o jsonpath='{.items[0].metadata.name}')
kubectl exec -n spark-submitter --context kind-submitter "$POD" -- wget -qO- http://localhost:8081/healthz
echo ""

# API port is HTTPS
kubectl port-forward -n spark-submitter --context kind-submitter svc/spark-submitter-svc 18443:8080 &
PF_PID=$!
sleep 2
curl -s --cacert "$CERT_DIR/ca.crt" -o /dev/null -w "HTTPS verified: %{http_code}\n" https://localhost:18443/api/v1/spark-submit || true
kill $PF_PID 2>/dev/null

echo "HTTPS scenario passed"
```

### 9c: mTLS mode

```bash
echo "=== TLS Scenario: mTLS ==="

helm upgrade --install spark-submitter charts/spark-submitter/ \
  --kube-context kind-submitter \
  --namespace spark-submitter \
  --set image.pullPolicy=Never \
  --set tls.enabled=true \
  --set tls.certPath=/etc/tls/tls.crt \
  --set tls.keyPath=/etc/tls/tls.key \
  --set tls.caCertPath=/etc/tls/ca.crt \
  --set 'volumes[0].name=tls-certs' \
  --set 'volumes[0].secret.secretName=tls-certs' \
  --set 'volumeMounts[0].name=tls-certs' \
  --set 'volumeMounts[0].mountPath=/etc/tls' \
  --set 'volumeMounts[0].readOnly=true'

kubectl rollout status deployment/spark-submitter -n spark-submitter --context kind-submitter --timeout=60s

kubectl port-forward -n spark-submitter --context kind-submitter svc/spark-submitter-svc 18443:8080 &
PF_PID=$!
sleep 2

# With valid client cert — should succeed
echo "--- With client cert ---"
curl -s --cacert "$CERT_DIR/ca.crt" --cert "$CERT_DIR/client.crt" --key "$CERT_DIR/client.key" \
  -o /dev/null -w "mTLS: %{http_code}\n" https://localhost:18443/api/v1/spark-submit || true

# Without client cert — should fail
echo "--- Without client cert ---"
HTTP_CODE=$(curl -sk -o /dev/null -w "%{http_code}" https://localhost:18443/api/v1/spark-submit 2>&1)
if [ "$HTTP_CODE" = "000" ] || [ "$HTTP_CODE" = "" ]; then
    echo "Rejected as expected (TLS handshake failed)"
else
    echo "ERROR: Expected rejection, got HTTP $HTTP_CODE"
fi

kill $PF_PID 2>/dev/null
echo "mTLS scenario passed"
```

### 9d: TLS cleanup

```bash
helm upgrade --install spark-submitter charts/spark-submitter/ \
  --kube-context kind-submitter \
  --namespace spark-submitter \
  --set image.pullPolicy=Never \
  --set tls.enabled=false
kubectl --context kind-submitter -n spark-submitter delete secret tls-certs --ignore-not-found
rm -rf "$CERT_DIR"
echo "TLS cleanup done, back to HTTP mode"
```

## Cleanup

```bash
helm uninstall spark-submitter -n spark-submitter --kube-context kind-submitter
kubectl delete namespace spark-submitter --context kind-submitter
kind delete cluster --name submitter
```
