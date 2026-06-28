#!/usr/bin/env bash
# Implanta um Redis no namespace do Redis, com PERSISTÊNCIA habilitada (AOF)
# gravando num volume EFÊMERO (emptyDir) montado em /data.
#
# O emptyDir é gravável sob a SCC restricted do OpenShift — o fsGroup do projeto
# torna o volume gravável pelo UID arbitrário do container, permitindo a
# persistência. É efêmero: sobrevive a reinícios do processo redis no pod, mas
# NÃO à recriação do pod. (Persistência durável real fica para a fase com
# PVC/StatefulSet.)
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
          # Persistência (AOF) + AUTH (requirepass). A senha vem do Secret via env;
          # sh -c permite que o \$REDIS_PASSWORD seja expandido dentro do container.
          command: ["sh", "-c", "exec redis-server --dir /data --appendonly yes --requirepass \"\$REDIS_PASSWORD\""]
          env:
            - name: REDIS_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: ${REDIS_AUTH_SECRET}
                  key: password
            # redis-cli (via oc exec) autentica automaticamente lendo REDISCLI_AUTH.
            - name: REDISCLI_AUTH
              valueFrom:
                secretKeyRef:
                  name: ${REDIS_AUTH_SECRET}
                  key: password
          ports:
            - containerPort: 6379
          volumeMounts:
            - name: redis-data
              mountPath: /data
          readinessProbe:
            tcpSocket:
              port: 6379
            initialDelaySeconds: 2
            periodSeconds: 5
      volumes:
        - name: redis-data
          emptyDir: {}
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
