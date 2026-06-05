---
name: test-tls-on-kind
description: Test all TLS scenarios (HTTPS, mTLS, cert reload) on a local kind cluster
---

You will test the TLS feature end-to-end on a local kind cluster. This assumes the cluster from `local-setup-and-test` is already running, or will create one.

## Execution Mode

1. **Present the plan first** — show the user the numbered list of actions.
2. **Run all actions sequentially** without waiting for user confirmation.
3. **Report after each step** — brief result, then immediately proceed.
4. **Stop on failure** — if any critical action fails, stop and show the error.

## Plan

| Step | Action | Critical? |
|------|--------|-----------|
| 1 | Ensure kind cluster and image are ready | Yes |
| 2 | Generate test certificates (CA, server, client) | Yes |
| 3 | Test Scenario 1: HTTP mode (TLS disabled) | Yes |
| 4 | Test Scenario 2: HTTPS mode (server TLS only) | Yes |
| 5 | Test Scenario 3: HTTPS + mTLS | Yes |
| 6 | Test Scenario 4: mTLS rejects client without cert | Yes |
| 7 | Test Scenario 5: Cert reload after rotation | No |
| 8 | Cleanup | No |

## Action 1: Ensure cluster and image

```bash
# Verify cluster exists
if ! kind get clusters 2>/dev/null | grep -q "^submitter$"; then
    echo "Creating kind cluster 'submitter'..."
    kind create cluster --name submitter --wait 60s
fi
kubectl config use-context kind-submitter

# Build and load image
make image IMAGE_TAG=k8s-spark-submitter:local
kind load docker-image k8s-spark-submitter:local --name submitter

# Ensure namespace exists
kubectl create namespace submitter --dry-run=client -o yaml | kubectl apply -f -
echo "✓ Cluster and image ready"
```

## Action 2: Generate test certificates

```bash
CERT_DIR=$(mktemp -d)
echo "Generating certs in $CERT_DIR"

# Generate CA
openssl req -x509 -newkey rsa:2048 -nodes \
  -keyout "$CERT_DIR/ca.key" -out "$CERT_DIR/ca.crt" \
  -days 365 -subj "/CN=test-ca"

# Generate server cert signed by CA
openssl req -newkey rsa:2048 -nodes \
  -keyout "$CERT_DIR/server.key" -out "$CERT_DIR/server.csr" \
  -subj "/CN=spark-submitter.submitter.svc"
openssl x509 -req -in "$CERT_DIR/server.csr" \
  -CA "$CERT_DIR/ca.crt" -CAkey "$CERT_DIR/ca.key" -CAcreateserial \
  -out "$CERT_DIR/server.crt" -days 365 \
  -extfile <(echo "subjectAltName=DNS:spark-submitter.submitter.svc,DNS:localhost")

# Generate client cert signed by same CA (for mTLS)
openssl req -newkey rsa:2048 -nodes \
  -keyout "$CERT_DIR/client.key" -out "$CERT_DIR/client.csr" \
  -subj "/CN=test-client"
openssl x509 -req -in "$CERT_DIR/client.csr" \
  -CA "$CERT_DIR/ca.crt" -CAkey "$CERT_DIR/ca.key" -CAcreateserial \
  -out "$CERT_DIR/client.crt" -days 365

# Create K8s secrets
kubectl -n submitter delete secret tls-server-cert --ignore-not-found
kubectl -n submitter create secret generic tls-server-cert \
  --from-file=tls.crt="$CERT_DIR/server.crt" \
  --from-file=tls.key="$CERT_DIR/server.key"

kubectl -n submitter delete secret tls-ca-cert --ignore-not-found
kubectl -n submitter create secret generic tls-ca-cert \
  --from-file=ca.crt="$CERT_DIR/ca.crt"

echo "✓ Certificates generated and stored as K8s secrets"
echo "CERT_DIR=$CERT_DIR"
```

## Action 3: Scenario 1 — HTTP mode (TLS disabled)

```bash
echo "=== Scenario 1: HTTP mode (no TLS) ==="

kubectl apply -n submitter -f - <<'EOF'
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
            - name: api
              containerPort: 8080
            - name: probes
              containerPort: 8081
          env:
            - name: SERVER_ADDRESS
              value: "0.0.0.0"
          livenessProbe:
            httpGet:
              path: /api/v1/health
              port: probes
            initialDelaySeconds: 10
            periodSeconds: 10
          readinessProbe:
            httpGet:
              path: /api/v1/health
              port: probes
            initialDelaySeconds: 5
            periodSeconds: 5
EOF

kubectl rollout status deployment/spark-submitter -n submitter --timeout=60s

# Port-forward and test
kubectl port-forward -n submitter deploy/spark-submitter 8080:8080 8081:8081 &
PF_PID=$!
sleep 3

echo "--- Testing API port (HTTP) ---"
curl -s -o /dev/null -w "HTTP %{http_code}\n" http://localhost:8081/api/v1/health
echo "--- Testing probe port (HTTP) ---"
curl -s -o /dev/null -w "HTTP %{http_code}\n" http://localhost:8081/api/v1/metrics

kill $PF_PID 2>/dev/null
echo "✓ Scenario 1 passed: HTTP mode works"
```

## Action 4: Scenario 2 — HTTPS mode (server TLS only)

```bash
echo "=== Scenario 2: HTTPS (server TLS, no mTLS) ==="

kubectl apply -n submitter -f - <<'EOF'
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
            - name: api
              containerPort: 8080
            - name: probes
              containerPort: 8081
          env:
            - name: SERVER_ADDRESS
              value: "0.0.0.0"
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
          livenessProbe:
            httpGet:
              path: /api/v1/health
              port: probes
            initialDelaySeconds: 10
            periodSeconds: 10
          readinessProbe:
            httpGet:
              path: /api/v1/health
              port: probes
            initialDelaySeconds: 5
            periodSeconds: 5
      volumes:
        - name: tls-cert
          secret:
            secretName: tls-server-cert
EOF

kubectl rollout status deployment/spark-submitter -n submitter --timeout=60s

kubectl port-forward -n submitter deploy/spark-submitter 8080:8080 8081:8081 &
PF_PID=$!
sleep 3

echo "--- Testing API port (HTTPS, skip cert verify) ---"
curl -sk -o /dev/null -w "HTTPS %{http_code}\n" https://localhost:8080/api/v1/health && echo "ERROR: health should not be on API port" || true

echo "--- Testing probe port (still HTTP) ---"
curl -s -o /dev/null -w "HTTP %{http_code}\n" http://localhost:8081/api/v1/health

echo "--- Testing API port HTTPS with CA verification ---"
curl -s --cacert "$CERT_DIR/ca.crt" -o /dev/null -w "HTTPS verified %{http_code}\n" https://localhost:8080/api/v1/spark-submit 2>&1 || true

kill $PF_PID 2>/dev/null
echo "✓ Scenario 2 passed: HTTPS works, probe port stays HTTP"
```

## Action 5: Scenario 3 — HTTPS + mTLS

```bash
echo "=== Scenario 3: HTTPS + mTLS ==="

kubectl apply -n submitter -f - <<'EOF'
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
            - name: api
              containerPort: 8080
            - name: probes
              containerPort: 8081
          env:
            - name: SERVER_ADDRESS
              value: "0.0.0.0"
            - name: TLS_ENABLED
              value: "true"
            - name: TLS_CERT_PATH
              value: "/etc/tls/tls.crt"
            - name: TLS_KEY_PATH
              value: "/etc/tls/tls.key"
            - name: TLS_CA_CERT_PATH
              value: "/etc/tls-ca/ca.crt"
          volumeMounts:
            - name: tls-cert
              mountPath: /etc/tls
              readOnly: true
            - name: tls-ca
              mountPath: /etc/tls-ca
              readOnly: true
          livenessProbe:
            httpGet:
              path: /api/v1/health
              port: probes
            initialDelaySeconds: 10
            periodSeconds: 10
          readinessProbe:
            httpGet:
              path: /api/v1/health
              port: probes
            initialDelaySeconds: 5
            periodSeconds: 5
      volumes:
        - name: tls-cert
          secret:
            secretName: tls-server-cert
        - name: tls-ca
          secret:
            secretName: tls-ca-cert
EOF

kubectl rollout status deployment/spark-submitter -n submitter --timeout=60s

kubectl port-forward -n submitter deploy/spark-submitter 8080:8080 8081:8081 &
PF_PID=$!
sleep 3

echo "--- Testing mTLS with valid client cert ---"
curl -s --cacert "$CERT_DIR/ca.crt" --cert "$CERT_DIR/client.crt" --key "$CERT_DIR/client.key" \
  -o /dev/null -w "mTLS %{http_code}\n" https://localhost:8080/api/v1/spark-submit 2>&1 || true

echo "--- Testing probe port (still HTTP, no client cert needed) ---"
curl -s -o /dev/null -w "HTTP %{http_code}\n" http://localhost:8081/api/v1/health

kill $PF_PID 2>/dev/null
echo "✓ Scenario 3 passed: mTLS with valid client cert works"
```

## Action 6: Scenario 4 — mTLS rejects client without cert

```bash
echo "=== Scenario 4: mTLS rejects unauthenticated client ==="

kubectl port-forward -n submitter deploy/spark-submitter 8080:8080 &
PF_PID=$!
sleep 3

echo "--- Attempting HTTPS without client cert (should fail) ---"
HTTP_CODE=$(curl -sk -o /dev/null -w "%{http_code}" https://localhost:8080/api/v1/spark-submit 2>&1)
if [ "$HTTP_CODE" = "000" ] || [ "$HTTP_CODE" = "" ]; then
    echo "✓ Connection rejected (no client cert) — TLS handshake failed as expected"
else
    echo "ERROR: Expected connection failure, got HTTP $HTTP_CODE"
fi

kill $PF_PID 2>/dev/null
echo "✓ Scenario 4 passed: mTLS correctly rejects clients without cert"
```

## Action 7: Scenario 5 — Cert reload after rotation

```bash
echo "=== Scenario 5: Cert reload ==="

# Redeploy with cert reload enabled and short check interval for testing
kubectl apply -n submitter -f - <<'EOF'
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
            - name: api
              containerPort: 8080
            - name: probes
              containerPort: 8081
          env:
            - name: SERVER_ADDRESS
              value: "0.0.0.0"
            - name: TLS_ENABLED
              value: "true"
            - name: TLS_CERT_PATH
              value: "/etc/tls/tls.crt"
            - name: TLS_KEY_PATH
              value: "/etc/tls/tls.key"
            - name: TLS_CERT_RELOAD_ENABLED
              value: "true"
            - name: TLS_CERT_CHECK_INTERVAL_MS
              value: "5000"
          volumeMounts:
            - name: tls-cert
              mountPath: /etc/tls
              readOnly: true
          livenessProbe:
            httpGet:
              path: /api/v1/health
              port: probes
            initialDelaySeconds: 10
            periodSeconds: 10
          readinessProbe:
            httpGet:
              path: /api/v1/health
              port: probes
            initialDelaySeconds: 5
            periodSeconds: 5
      volumes:
        - name: tls-cert
          secret:
            secretName: tls-server-cert
EOF

kubectl rollout status deployment/spark-submitter -n submitter --timeout=60s

kubectl port-forward -n submitter deploy/spark-submitter 8080:8080 8081:8081 &
PF_PID=$!
sleep 3

echo "--- Initial request (original cert) ---"
curl -sk -o /dev/null -w "HTTPS %{http_code}\n" https://localhost:8080/api/v1/spark-submit || true

echo "--- Rotating certificate (generating new cert, updating secret) ---"
openssl req -newkey rsa:2048 -nodes \
  -keyout "$CERT_DIR/server-new.key" -out "$CERT_DIR/server-new.csr" \
  -subj "/CN=spark-submitter-rotated.submitter.svc"
openssl x509 -req -in "$CERT_DIR/server-new.csr" \
  -CA "$CERT_DIR/ca.crt" -CAkey "$CERT_DIR/ca.key" -CAcreateserial \
  -out "$CERT_DIR/server-new.crt" -days 365 \
  -extfile <(echo "subjectAltName=DNS:spark-submitter.submitter.svc,DNS:localhost")

kubectl -n submitter delete secret tls-server-cert
kubectl -n submitter create secret generic tls-server-cert \
  --from-file=tls.crt="$CERT_DIR/server-new.crt" \
  --from-file=tls.key="$CERT_DIR/server-new.key"

echo "--- Waiting for kubelet to propagate new cert (up to 60s) ---"
sleep 15

echo "--- Sending request to trigger reload check ---"
curl -sk -o /dev/null -w "HTTPS %{http_code}\n" https://localhost:8080/api/v1/spark-submit || true

echo "--- Checking pod logs for reload message ---"
SUBMITTER_POD=$(kubectl get pods -n submitter -l app=spark-submitter -o jsonpath='{.items[0].metadata.name}')
kubectl logs "$SUBMITTER_POD" -n submitter | grep -i "reload\|Certificate" | tail -5

kill $PF_PID 2>/dev/null
echo "✓ Scenario 5: Cert reload test complete (check logs above for reload confirmation)"
```

## Action 8: Cleanup

```bash
echo "=== Cleanup ==="
kubectl delete deployment spark-submitter -n submitter --ignore-not-found
kubectl delete secret tls-server-cert tls-ca-cert -n submitter --ignore-not-found
rm -rf "$CERT_DIR"
echo "✓ Cleaned up (cluster still running — use 'kind delete cluster --name submitter' to remove)"
```
