#!/usr/bin/env bash
# Implanta o proxy (imagem do registry interno), com TLS de borda:
#  - monta o Secret de TLS em /var/certificados/servidor/ e aponta o cert/key
#    por env var (o "o quê" — ligar TLS — está em %prod no application.properties);
#  - Service expõe a porta TLS 8443;
#  - exposição via Ingress (restrição corporativa): o Route passthrough nasce
#    automaticamente do Ingress (anotação route.openshift.io/termination).
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
            # Nível de log da app (DEBUG no CRC; toggle por ambiente — DESIGN 8.1).
            - name: QUARKUS_LOG_CATEGORY__IO_GITHUB_CLAUDINEYNS__LEVEL
              value: "${APP_LOG_LEVEL}"
            # Caminhos do cert/key (Secret montado) — "onde" do TLS, via env.
            - name: QUARKUS_TLS_HTTPS_KEY_STORE_PEM_PROXY_CERT
              value: /var/certificados/servidor/tls.crt
            - name: QUARKUS_TLS_HTTPS_KEY_STORE_PEM_PROXY_KEY
              value: /var/certificados/servidor/tls.key
            # Chave mestra (HMAC) do interceptor de credenciais (DESIGN 6.1):
            # env PROXY_AUTH_MASTER_KEY → propriedade proxy.auth.master-key.
            - name: PROXY_AUTH_MASTER_KEY
              valueFrom:
                secretKeyRef:
                  name: ${AUTH_SECRET}
                  key: master-key
            # Allowlist de hashes SHA-256(ACCESS_KEY) do ConfigMap (DESIGN 6.1):
            # env PROXY_AUTH_ACCESS_KEY_HASHES → propriedade proxy.auth.access-key-hashes.
            - name: PROXY_AUTH_ACCESS_KEY_HASHES
              valueFrom:
                configMapKeyRef:
                  name: ${ACL_CONFIGMAP}
                  key: access-key-hashes
          ports:
            - name: https
              containerPort: 8443
            - name: management
              containerPort: 9000
          # Health vive na interface de management (9000, plaintext), fora da borda.
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
          volumeMounts:
            - name: tls
              mountPath: /var/certificados/servidor
              readOnly: true
      volumes:
        - name: tls
          secret:
            secretName: ${TLS_SECRET}
---
apiVersion: v1
kind: Service
metadata:
  name: ${APP_NAME}
spec:
  selector:
    app: ${APP_NAME}
  ports:
    - name: https
      port: 8443
      targetPort: 8443
    - name: management
      port: 9000
      targetPort: 9000
---
# Exposição via Ingress (restrição corporativa): o OpenShift gera o Route
# automaticamente. A anotação torna o Route auto-gerado PASSTHROUGH (TLS termina
# no pod). Sem bloco tls: no Ingress, pois o router só repassa os bytes.
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: ${APP_NAME}
  annotations:
    route.openshift.io/termination: passthrough
spec:
  rules:
    - host: ${EDGE_HOST}
      http:
        paths:
          - path: ""
            pathType: ImplementationSpecific
            backend:
              service:
                name: ${APP_NAME}
                port:
                  number: 8443
YAML

# Força um novo rollout para puxar o :latest recém-construído.
oc --context "$OC_CXT" -n "$PROXY_NAMESPACE" rollout restart deploy/${APP_NAME}
oc --context "$OC_CXT" -n "$PROXY_NAMESPACE" rollout status deploy/${APP_NAME} --timeout=180s

echo "Route gerado automaticamente a partir do Ingress:"
oc --context "$OC_CXT" -n "$PROXY_NAMESPACE" get route -o wide 2>/dev/null || true
