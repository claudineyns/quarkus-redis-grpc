#!/usr/bin/env bash
# Implanta o proxy referenciando a imagem construída no registry interno.
# Aponta o cliente Redis para o Service do Redis (namespace separado) via env.
set -euo pipefail
cd "$(dirname "$0")"
source ./env.sh

IMAGE="${INTERNAL_REGISTRY}/${PROXY_NAMESPACE}/${APP_NAME}:latest"
REDIS_HOST="${REDIS_NAME}.${REDIS_NAMESPACE}.svc"

echo "implantando proxy '${APP_NAME}' em '${PROXY_NAMESPACE}'..."
oc --context "$OC_CXT" -n "$PROXY_NAMESPACE" apply -f - <<YAML
apiVersion: apps/v1
kind: Deployment
metadata:
  name: ${APP_NAME}
  labels:
    app: ${APP_NAME}
spec:
  replicas: 1
  selector:
    matchLabels:
      app: ${APP_NAME}
  template:
    metadata:
      labels:
        app: ${APP_NAME}
    spec:
      containers:
        - name: ${APP_NAME}
          image: ${IMAGE}
          env:
            # Código agnóstico: endereço do Redis injetado por configuração.
            - name: QUARKUS_REDIS_HOSTS
              value: "redis://${REDIS_HOST}:6379"
          ports:
            - name: grpc
              containerPort: 8080
            - name: management
              containerPort: 9000
          # Health vive na interface de management (porta 9000), fora da borda.
          readinessProbe:
            httpGet:
              path: /q/health/ready
              port: 9000
            initialDelaySeconds: 5
            periodSeconds: 5
          livenessProbe:
            httpGet:
              path: /q/health/live
              port: 9000
            initialDelaySeconds: 10
            periodSeconds: 10
---
apiVersion: v1
kind: Service
metadata:
  name: ${APP_NAME}
spec:
  selector:
    app: ${APP_NAME}
  ports:
    - name: grpc
      port: 8080
      targetPort: 8080
    - name: management
      port: 9000
      targetPort: 9000
YAML

oc --context "$OC_CXT" -n "$PROXY_NAMESPACE" rollout status deploy/${APP_NAME} --timeout=180s
