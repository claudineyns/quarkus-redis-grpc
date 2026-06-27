"""Demo/validação do GETDEL contra o proxy.

GETDEL devolve o valor e apaga a chave. Validamos o valor retornado e, em
seguida, confirmamos a exclusão com um GET (deve voltar nil). Também cobre
chave ausente (nil) e wrong-type (erro).

Pré-requisito: o chamador semeou demo:getdel:present e o hash demo:getdel:hash.
Uso: python getdel.py [target]   (default: localhost:18080)
"""
import sys

import client  # define sys.path p/ os stubs e expõe connect()/get()
import grpc
import string_pb2

PRESENT = "demo:getdel:present"
ABSENT = "demo:getdel:absent"
HASH = "demo:getdel:hash"


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

    # Chave presente: GETDEL devolve o valor e apaga.
    r = stub.GetDel(string_pb2.GetDelRequest(key=PRESENT))
    print(f"--> GETDEL {PRESENT}\n<-- value={render(r)}")
    check(r.HasField("value") and r.value.decode() == "v", f"{PRESENT} == 'v'")
    found, _ = client.get(stub, PRESENT)
    check(not found, f"{PRESENT} apagada (GET -> nil)")

    # Chave ausente: nil.
    r = stub.GetDel(string_pb2.GetDelRequest(key=ABSENT))
    print(f"--> GETDEL {ABSENT}\n<-- value={render(r)}")
    check(not r.HasField("value"), f"{ABSENT} -> nil")

    # Tipo errado: erro.
    print(f"--> GETDEL {HASH} (hash)")
    try:
        stub.GetDel(string_pb2.GetDelRequest(key=HASH))
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
    print("GETDEL smoke OK")


if __name__ == "__main__":
    main()
