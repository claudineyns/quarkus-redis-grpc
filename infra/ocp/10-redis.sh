#!/usr/bin/env bash
# Implanta um Redis efêmero (sem persistência) no namespace do Redis.
# Persistência desligada (--save "" --appendonly no) para rodar sob a SCC
# restricted do OpenShift (UID arbitrário, sem escrita em /data).
set -euo pipefail
cd "$(dirname "$0")"
source ./env.sh

echo "implantando Redis em '$REDIS_NAMESPACE'..."
oc --context "$OC_CXT" -n "$REDIS_NAMESPACE" apply -f - <<YAML
apiVersion: apps/v1
kind: Deployment
metadata:
  name: ${REDIS_NAME}
  labels:
    app: ${REDIS_NAME}
spec:
  replicas: 1
  selector:
    matchLabels:
      app: ${REDIS_NAME}
  template:
    metadata:
      labels:
        app: ${REDIS_NAME}
    spec:
      containers:
        - name: redis
          image: ${REDIS_IMAGE}
          args: ["redis-server", "--save", "", "--appendonly", "no"]
          ports:
            - containerPort: 6379
          readinessProbe:
            tcpSocket:
              port: 6379
            initialDelaySeconds: 2
            periodSeconds: 5
---
apiVersion: v1
kind: Service
metadata:
  name: ${REDIS_NAME}
spec:
  selector:
    app: ${REDIS_NAME}
  ports:
    - port: 6379
      targetPort: 6379
YAML

oc --context "$OC_CXT" -n "$REDIS_NAMESPACE" rollout status deploy/${REDIS_NAME} --timeout=120s
