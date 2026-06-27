"""Demo/validação do GETEX contra o proxy.

Exercita os modos do GETEX (EX, PERSIST, sem opção, ausente, wrong-type) e
mostra o valor/nil/erro. O efeito sobre o TTL é conferido pelo script bash
(via redis-cli), pois ainda não há RPC de TTL.

Pré-requisito: o chamador semeou demo:getex:{a,b,c} e o hash demo:getex:hash.
Uso: python getex.py [target]   (default: localhost:18080)
"""
import sys

import client  # define sys.path p/ os stubs e expõe connect()
import grpc
import string_pb2

A = "demo:getex:a"          # sem TTL → GETEX EX define TTL
B = "demo:getex:b"          # com TTL → GETEX PERSIST remove TTL
C = "demo:getex:c"          # com TTL → GETEX sem opção mantém TTL
ABSENT = "demo:getex:absent"
HASH = "demo:getex:hash"


def render(resp):
    return repr(resp.value.decode("utf-8")) if resp.HasField("value") else "nil"


def main():
    target = sys.argv[1] if len(sys.argv) > 1 else "localhost:18080"
    channel, stub = client.connect(target)
    print(f"Conectado a {target} (StringService).\n")

    failures = 0

    def check(condition, msg):
        nonlocal failures
        print(f"    {'OK   ' if condition else 'FALHA'} {msg}")
        if not condition:
            failures += 1

    r = stub.GetEx(string_pb2.GetExRequest(key=A, ex_seconds=100))
    print(f"--> GETEX {A} EX 100\n<-- value={render(r)}")
    check(r.HasField("value") and r.value.decode() == "va", f"{A} == 'va'")

    r = stub.GetEx(string_pb2.GetExRequest(key=B, persist=True))
    print(f"--> GETEX {B} PERSIST\n<-- value={render(r)}")
    check(r.HasField("value") and r.value.decode() == "vb", f"{B} == 'vb'")

    r = stub.GetEx(string_pb2.GetExRequest(key=C))
    print(f"--> GETEX {C} (sem opção)\n<-- value={render(r)}")
    check(r.HasField("value") and r.value.decode() == "vc", f"{C} == 'vc'")

    r = stub.GetEx(string_pb2.GetExRequest(key=ABSENT))
    print(f"--> GETEX {ABSENT}\n<-- value={render(r)}")
    check(not r.HasField("value"), f"{ABSENT} -> nil")

    print(f"--> GETEX {HASH} (hash)")
    try:
        stub.GetEx(string_pb2.GetExRequest(key=HASH))
        check(False, "esperava erro, veio sucesso")
    except grpc.RpcError as err:
        print(f"<-- ERROR status={err.code().name}")
        check(err.code() == grpc.StatusCode.FAILED_PRECONDITION,
              "wrong-type -> FAILED_PRECONDITION")

    channel.close()

    print()
    if failures:
        print(f"{failures} verificação(ões) falharam")
        sys.exit(1)
    print("GETEX smoke OK")


if __name__ == "__main__":
    main()
