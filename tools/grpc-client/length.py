"""Demo/validação de APPEND + STRLEN contra o proxy.

APPEND cria/concatena e devolve o comprimento resultante; STRLEN devolve o
comprimento (0 se ausente). Validamos os comprimentos e o conteúdo (via GET).

Pré-requisito: o chamador limpou demo:len:k/absent e criou o hash demo:len:hash.
Uso: python length.py [target]   (default: localhost:18080)
"""
import sys

import client  # define sys.path p/ os stubs e expõe connect()/get()
import grpc
import string_pb2

K = "demo:len:k"
ABSENT = "demo:len:absent"
HASH = "demo:len:hash"


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

    def value_of(key):
        found, raw = client.get(stub, key)
        return raw.decode("utf-8") if found else None

    # APPEND cria a chave (ausente) e devolve o comprimento.
    r = stub.Append(string_pb2.AppendRequest(key=K, value=b"Hello"))
    print(f"--> APPEND {K} 'Hello'\n<-- length={r.length}")
    check(r.length == 5, "APPEND cria -> length 5")
    check(value_of(K) == "Hello", f"{K} == 'Hello'")

    # APPEND concatena.
    r = stub.Append(string_pb2.AppendRequest(key=K, value=b" World"))
    print(f"--> APPEND {K} ' World'\n<-- length={r.length}")
    check(r.length == 11, "APPEND concatena -> length 11")
    check(value_of(K) == "Hello World", f"{K} == 'Hello World'")

    # STRLEN da chave existente.
    r = stub.Strlen(string_pb2.StrlenRequest(key=K))
    print(f"--> STRLEN {K}\n<-- length={r.length}")
    check(r.length == 11, "STRLEN -> 11")

    # STRLEN de chave ausente -> 0.
    r = stub.Strlen(string_pb2.StrlenRequest(key=ABSENT))
    print(f"--> STRLEN {ABSENT}\n<-- length={r.length}")
    check(r.length == 0, "STRLEN ausente -> 0")

    # Tipo errado -> erro.
    print(f"--> STRLEN {HASH} (hash)")
    try:
        stub.Strlen(string_pb2.StrlenRequest(key=HASH))
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
    print("APPEND/STRLEN smoke OK")


if __name__ == "__main__":
    main()
