---
name: local-setup-and-test
description: Set up local kind cluster for k8s-spark-submitter and run a SparkPi verification job end-to-end
---

You will set up a complete local Kubernetes environment for k8s-spark-submitter using kind (Kubernetes in Docker).

## Execution Mode

1. **Present the plan first** -- show the user the numbered list of actions below so they know what will happen.
2. **Run all actions sequentially** -- execute each action one after another without waiting for user confirmation between steps.
3. **Report after each step** -- after each action completes, briefly report what was done and the result (success or failure), then immediately proceed to the next action.
4. **Stop on failure** -- if any critical action fails, stop execution and show the error. Do NOT continue to the next action.
5. **Track progress** -- use the todo list tool to track action status (pending/in_progress/completed/failed) so the user can see overall progress.

## Plan

| Step | Action | Critical? |
|------|--------|-----------|
| 1 | Verify Docker is running | Yes -- cannot proceed without it |
| 2 | Install kind if not present | Yes -- required for cluster |
| 3 | Create kind cluster "submitter" | Yes -- required for deployment |
| 4 | Build and load Docker image | Yes -- required for deployment |
| 5 | Create namespace and apply K8s resources | Yes -- required for service |
| 6 | Verify deployment is ready | No -- informational |

## Action 1: Check Docker

```bash
docker ps >/dev/null 2>&1 || { echo "ERROR: Docker is not running. Start Docker Desktop first."; exit 1; }
echo "✓ Docker is running"
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
echo "✓ kind installed: $(kind version)"
```

## Action 3: Create kind cluster

```bash
if kind get clusters 2>/dev/null | grep -q "^submitter$"; then
    echo "✓ Cluster 'submitter' exists"
else
    echo "Creating kind cluster 'submitter'..."
    kind create cluster --name submitter --wait 60s
fi
kubectl config use-context kind-submitter
echo "✓ Cluster ready"
```

## Action 4: Build and load image

```bash
echo "Building JAR and Docker image..."
make image IMAGE_TAG=k8s-spark-submitter:local
echo "Loading image into kind..."
kind load docker-image k8s-spark-submitter:local --name submitter
echo "✓ Image ready"
```

## Action 5: Deploy all Kubernetes resources

```bash
# Create namespace
kubectl apply -f - <<'EOF'
apiVersion: v1
kind: Namespace
metadata:
  name: submitter
  labels:
    name: submitter
EOF
kubectl wait --for=jsonpath='{.status.phase}'=Active namespace/submitter --timeout=30s

# Apply submitter RBAC
kubectl apply -f - <<'EOF'
apiVersion: v1
kind: ServiceAccount
metadata:
  name: spark-submitter
  namespace: submitter
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRole
metadata:
  name: spark-submitter-role
rules:
  - apiGroups: [""]
    resources: ["pods", "services", "configmaps", "persistentvolumeclaims"]
    verbs: ["create", "get", "list", "watch", "delete", "update", "patch"]
  - apiGroups: [""]
    resources: ["pods/log"]
    verbs: ["get", "list"]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata:
  name: spark-submitter-binding
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: ClusterRole
  name: spark-submitter-role
subjects:
  - kind: ServiceAccount
    name: spark-submitter
    namespace: submitter
EOF

# Apply Spark RBAC
kubectl apply -f - <<'EOF'
apiVersion: v1
kind: ServiceAccount
metadata:
  name: spark
  namespace: submitter
---
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: spark-role
  namespace: submitter
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
  namespace: submitter
roleRef:
  kind: Role
  name: spark-role
  apiGroup: rbac.authorization.k8s.io
subjects:
  - kind: ServiceAccount
    name: spark
    namespace: submitter
EOF

# Apply service and deployment
kubectl apply -f - <<'EOF'
apiVersion: v1
kind: Service
metadata:
  name: spark-submitter
  namespace: submitter
spec:
  type: NodePort
  ports:
    - port: 8080
      targetPort: http
      nodePort: 30080
      name: http
  selector:
    app: spark-submitter
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: spark-submitter
  namespace: submitter
spec:
  replicas: 1
  selector:
    matchLabels:
      app: spark-submitter
  template:
    metadata:
      labels:
        app: spark-submitter
    spec:
      serviceAccountName: spark-submitter
      containers:
        - name: spark-submitter
          image: k8s-spark-submitter:local
          imagePullPolicy: Never
          ports:
            - name: http
              containerPort: 8080
          env:
            - name: SERVER_ADDRESS
              value: "0.0.0.0"
          livenessProbe:
            httpGet:
              path: /health
              port: 8080
            initialDelaySeconds: 15
            periodSeconds: 10
          readinessProbe:
            httpGet:
              path: /health
              port: 8080
            initialDelaySeconds: 5
            periodSeconds: 5
          resources:
            requests:
              memory: "512Mi"
              cpu: "250m"
            limits:
              memory: "1Gi"
              cpu: "500m"
EOF

echo "✓ Resources deployed"
```

## Action 6: Verify deployment

```bash
kubectl wait --for=condition=available --timeout=120s deployment/spark-submitter -n submitter
echo ""
echo "✅ Setup complete!"
echo ""
kubectl get pods -n submitter
echo ""
kubectl get svc -n submitter
echo ""
echo "Access API: kubectl port-forward -n submitter svc/spark-submitter 8080:8080"
echo "Test: curl -X POST http://localhost:8080/api/v1/spark/submit -H 'Content-Type: application/json' -d '{\"spark_submit_args\":[\"--master\",\"k8s://https://kubernetes.default.svc\",\"--deploy-mode\",\"cluster\",\"--name\",\"test\",\"--class\",\"org.apache.spark.examples.SparkPi\",\"--conf\",\"spark.kubernetes.namespace=submitter\",\"--conf\",\"spark.kubernetes.authenticate.driver.serviceAccountName=spark\",\"--conf\",\"spark.kubernetes.container.image=spark:4.0.1\",\"local:///opt/spark/examples/jars/spark-examples_2.13-4.0.1.jar\"]}'"
echo ""
```

## Action 7: Submit SparkPi job and verify

This action has multiple sub-steps. Run them in order and report each sub-step result.

### 7a: Port-forward and submit SparkPi

```bash
# Start port-forward in background
kubectl port-forward -n submitter svc/spark-submitter 8080:8080 &
PF_PID=$!
sleep 2

# Submit SparkPi using open-source Spark 4.0.1 image
echo "--- Submitting SparkPi job ---"
RESPONSE=$(curl -s -w "\n%{http_code}" -X POST http://localhost:8080/api/v1/spark/submit \
  -H 'Content-Type: application/json' \
  -d '{
    "spark_submit_args": [
      "--master", "k8s://https://kubernetes.default.svc",
      "--deploy-mode", "cluster",
      "--name", "spark-pi-test",
      "--class", "org.apache.spark.examples.SparkPi",
      "--conf", "spark.kubernetes.namespace=submitter",
      "--conf", "spark.kubernetes.authenticate.driver.serviceAccountName=spark",
      "--conf", "spark.kubernetes.container.image=spark:4.0.1",
      "local:///opt/spark/examples/jars/spark-examples_2.13-4.0.1.jar"
    ]
  }')

HTTP_CODE=$(echo "$RESPONSE" | tail -1)
BODY=$(echo "$RESPONSE" | head -n -1)

echo "HTTP Status: $HTTP_CODE"
echo "Response: $BODY"

if [ "$HTTP_CODE" -ge 200 ] && [ "$HTTP_CODE" -lt 300 ]; then
    echo "--- Submission accepted ---"
else
    echo "--- ERROR: Submission failed ---"
fi
```

### 7b: Check submitter pod logs for submission proof

```bash
echo "--- Submitter pod logs (last 50 lines) ---"
SUBMITTER_POD=$(kubectl get pods -n submitter -l app=spark-submitter -o jsonpath='{.items[0].metadata.name}')
kubectl logs "$SUBMITTER_POD" -n submitter --tail=50
```

### 7c: Monitor driver pod status through stages

```bash
echo "--- Waiting for driver pod to appear ---"
for i in $(seq 1 30); do
    DRIVER_POD=$(kubectl get pods -n submitter -l spark-role=driver -o jsonpath='{.items[0].metadata.name}' 2>/dev/null)
    if [ -n "$DRIVER_POD" ]; then
        echo "Driver pod found: $DRIVER_POD"
        break
    fi
    echo "Waiting... ($i/30)"
    sleep 5
done

if [ -z "$DRIVER_POD" ]; then
    echo "ERROR: Driver pod never appeared after 150s"
    # Show all pods for debugging
    kubectl get pods -n submitter
    # Kill port-forward
    kill $PF_PID 2>/dev/null
    exit 1
fi

echo ""
echo "--- Driver pod status stages ---"
PREV_PHASE=""
for i in $(seq 1 60); do
    PHASE=$(kubectl get pod "$DRIVER_POD" -n submitter -o jsonpath='{.status.phase}' 2>/dev/null)
    if [ "$PHASE" != "$PREV_PHASE" ]; then
        echo "[$(date +%H:%M:%S)] Driver pod phase: $PHASE"
        PREV_PHASE="$PHASE"
    fi
    if [ "$PHASE" = "Succeeded" ] || [ "$PHASE" = "Failed" ]; then
        break
    fi
    sleep 5
done

echo ""
echo "--- Final driver pod describe (events) ---"
kubectl describe pod "$DRIVER_POD" -n submitter | tail -20
```

### 7d: Show driver pod logs (success or failure)

```bash
DRIVER_POD=$(kubectl get pods -n submitter -l spark-role=driver -o jsonpath='{.items[0].metadata.name}' 2>/dev/null)
PHASE=$(kubectl get pod "$DRIVER_POD" -n submitter -o jsonpath='{.status.phase}' 2>/dev/null)

echo "=== Driver pod: $DRIVER_POD ==="
echo "=== Final status: $PHASE ==="
echo ""

if [ "$PHASE" = "Succeeded" ]; then
    echo "--- SUCCESS: Driver pod logs ---"
    kubectl logs "$DRIVER_POD" -n submitter | tail -30
    echo ""
    echo "--- SparkPi result ---"
    kubectl logs "$DRIVER_POD" -n submitter | grep -i "pi is roughly"
elif [ "$PHASE" = "Failed" ]; then
    echo "--- FAILED: Driver pod logs (last 50 lines) ---"
    kubectl logs "$DRIVER_POD" -n submitter --tail=50
    echo ""
    echo "--- Container exit codes ---"
    kubectl get pod "$DRIVER_POD" -n submitter -o jsonpath='{range .status.containerStatuses[*]}Container: {.name} State: {.state} LastState: {.lastState}{"\n"}{end}'
else
    echo "--- UNKNOWN/TIMEOUT: Driver pod logs (last 50 lines) ---"
    kubectl logs "$DRIVER_POD" -n submitter --tail=50
fi

# Cleanup port-forward
kill $PF_PID 2>/dev/null
echo ""
echo "--- All pods final state ---"
kubectl get pods -n submitter -o wide
```

## Cleanup

```bash
kind delete cluster --name submitter
```
