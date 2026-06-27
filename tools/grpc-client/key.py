"""Demo/validação do KeyService (fatia 1: DEL/UNLINK/EXISTS/TYPE).

Exercita TYPE (string/hash/none), EXISTS (contagem), DEL (remove + conta) e
UNLINK (remove + conta), confirmando os efeitos. Resumo OK/FALHA.

Pré-requisito: o chamador semeou demo:key:a, demo:key:b (string) e demo:key:h
(hash). Uso: python key.py [target]   (default: localhost:18080)
"""
import sys

import client  # garante generated/ no sys.path
import grpc
import key_pb2
import key_pb2_grpc

A = "demo:key:a"
B = "demo:key:b"
H = "demo:key:h"
ABSENT = "demo:key:absent"


def main():
    target = sys.argv[1] if len(sys.argv) > 1 else "localhost:18080"
    channel = grpc.insecure_channel(target)
    stub = key_pb2_grpc.KeyServiceStub(channel)
    print(f"Conectado a {target} (KeyService).\n")

    failures = 0

    def check(condition, msg):
        nonlocal failures
        print(f"    {'OK   ' if condition else 'FALHA'} {msg}")
        if not condition:
            failures += 1

    r = stub.Type(key_pb2.TypeRequest(key=A))
    print(f"--> TYPE {A}\n<-- type={r.type!r}")
    check(r.type == "string", f"{A} -> 'string'")

    r = stub.Type(key_pb2.TypeRequest(key=H))
    print(f"--> TYPE {H}\n<-- type={r.type!r}")
    check(r.type == "hash", f"{H} -> 'hash'")

    r = stub.Type(key_pb2.TypeRequest(key=ABSENT))
    print(f"--> TYPE {ABSENT}\n<-- type={r.type!r}")
    check(r.type == "none", f"{ABSENT} -> 'none'")

    r = stub.Exists(key_pb2.ExistsRequest(keys=[A, B, ABSENT]))
    print(f"--> EXISTS [{A}, {B}, {ABSENT}]\n<-- count={r.count}")
    check(r.count == 2, "EXISTS -> 2")

    r = stub.Del(key_pb2.DelRequest(keys=[A, B, ABSENT]))
    print(f"--> DEL [{A}, {B}, {ABSENT}]\n<-- count={r.count}")
    check(r.count == 2, "DEL -> 2")

    r = stub.Exists(key_pb2.ExistsRequest(keys=[A]))
    print(f"--> EXISTS [{A}] (após DEL)\n<-- count={r.count}")
    check(r.count == 0, f"{A} apagada")

    r = stub.Unlink(key_pb2.UnlinkRequest(keys=[H]))
    print(f"--> UNLINK [{H}]\n<-- count={r.count}")
    check(r.count == 1, "UNLINK -> 1")

    channel.close()

    print()
    if failures:
        print(f"{failures} verificação(ões) falharam")
        sys.exit(1)
    print("KeyService (fatia 1) smoke OK")


if __name__ == "__main__":
    main()
