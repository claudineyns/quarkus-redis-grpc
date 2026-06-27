# quarkus-redis-grpc

A north-south proxy that exposes Redis commands through a gRPC API.

## Overview

This service is a **gRPC-over-Redis gateway**. It lets external clients reach an
internal cluster Redis by **traversing a corporate edge that only forwards HTTP**:
Redis' RESP (raw TCP) protocol cannot cross an HTTP route, but wrapping the
commands in gRPC (HTTP/2) tunnels them through an OpenShift **passthrough** route.

gRPC methods are a **1:1 representation of Redis commands** — the proxy carries no
business logic; it translates, forwards, and translates back.

## Key characteristics

- **Quarkus** (Red Hat build) on **Java 21**, reactive gRPC with **Mutiny**.
- **Low-level Redis client** (`ReactiveRedisAPI`) for faithful, binary-safe 1:1 mapping.
- Supports Redis in **standalone** or **Sentinel** mode (configuration-driven).
- Command families: **KEY/VALUE**, **KEY/HASH**, and **SET** (plus generic key ops).
- **Mandatory edge TLS** (one-way, no mTLS); caller auth via **static token** in
  gRPC metadata; Redis **AUTH** enabled.
- Built to run behind an OpenShift Service and an edge ingress/route with byte
  passthrough.

## Status

Early stage — the architecture and design are captured in [docs/DESIGN.md](docs/DESIGN.md).
The client-side Quarkus extension is planned as a separate adjacent project once the
proxy is stable.
