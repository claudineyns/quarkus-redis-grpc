"""Demo/validação do KeyService — fatia 2 (expiração).

Exercita EXPIRE/PEXPIRE/EXPIREAT/PERSIST e TTL, incluindo as condições NX e GT.
O TTL é lido pelo próprio RPC Ttl. Resumo OK/FALHA.

Pré-requisito: o chamador semeou demo:kx:k, demo:kx:k2, demo:kx:k3 (strings sem
TTL). Uso: python keyexp.py [target]   (default: localhost:18080)
"""
import sys
import time

import client  # garante generated/ no sys.path e expõe make_channel (TLS-aware)
import key_pb2
import key_pb2_grpc

K = "demo:kx:k"
K2 = "demo:kx:k2"
K3 = "demo:kx:k3"
ABSENT = "demo:kx:absent"


def main():
    target = sys.argv[1] if len(sys.argv) > 1 else "localhost:18080"
    channel = client.make_channel(target)  # TLS se REDIS_GRPC_CA estiver definido
    stub = key_pb2_grpc.KeyServiceStub(channel)
    print(f"Conectado a {target} (KeyService).\n")

    failures = 0

    def check(condition, msg):
        nonlocal failures
        print(f"    {'OK   ' if condition else 'FALHA'} {msg}")
        if not condition:
            failures += 1

    def ttl(key):
        return stub.Ttl(key_pb2.TtlRequest(key=key)).value

    # EXPIRE define TTL.
    a = stub.Expire(key_pb2.ExpireRequest(key=K, seconds=100))
    print(f"--> EXPIRE {K} 100\n<-- applied={a.applied}  ttl={ttl(K)}")
    check(a.applied and ttl(K) > 0, "EXPIRE define TTL")

    # PERSIST remove TTL.
    a = stub.Persist(key_pb2.PersistRequest(key=K))
    print(f"--> PERSIST {K}\n<-- applied={a.applied}  ttl={ttl(K)}")
    check(a.applied and ttl(K) == -1, "PERSIST remove TTL (-1)")

    # TTL de chave ausente = -2.
    print(f"--> TTL {ABSENT}\n<-- value={ttl(ABSENT)}")
    check(ttl(ABSENT) == -2, "TTL ausente == -2")

    # NX: aplica sem TTL; não aplica com TTL.
    a = stub.Expire(key_pb2.ExpireRequest(
        key=K, seconds=100, condition=key_pb2.EXPIRE_CONDITION_NX))
    check(a.applied, "EXPIRE NX (sem TTL) -> applied")
    a = stub.Expire(key_pb2.ExpireRequest(
        key=K, seconds=200, condition=key_pb2.EXPIRE_CONDITION_NX))
    check(not a.applied, "EXPIRE NX (com TTL) -> not applied")

    # GT: só aumenta (TTL atual = 100).
    a = stub.Expire(key_pb2.ExpireRequest(
        key=K, seconds=50, condition=key_pb2.EXPIRE_CONDITION_GT))
    check(not a.applied, "EXPIRE GT 50 -> not applied")
    a = stub.Expire(key_pb2.ExpireRequest(
        key=K, seconds=500, condition=key_pb2.EXPIRE_CONDITION_GT))
    check(a.applied, "EXPIRE GT 500 -> applied")

    # PEXPIRE (ms).
    a = stub.PExpire(key_pb2.PExpireRequest(key=K2, millis=100_000))
    print(f"--> PEXPIRE {K2} 100000\n<-- applied={a.applied}  ttl={ttl(K2)}")
    check(a.applied and ttl(K2) > 0, "PEXPIRE define TTL")

    # EXPIREAT (timestamp absoluto futuro).
    future = int(time.time()) + 100
    a = stub.ExpireAt(key_pb2.ExpireAtRequest(key=K3, unix_seconds=future))
    print(f"--> EXPIREAT {K3} {future}\n<-- applied={a.applied}  ttl={ttl(K3)}")
    check(a.applied and ttl(K3) > 0, "EXPIREAT define TTL")

    channel.close()

    print()
    if failures:
        print(f"{failures} verificação(ões) falharam")
        sys.exit(1)
    print("KeyService (fatia 2) smoke OK")


if __name__ == "__main__":
    main()
