"""Demo/validação dos contadores (INCR/INCRBY) contra o proxy.

Mostra o fluxo e verifica o valor resultante. Usa SET para resetar o estado
inicial (ainda não há DEL). Resumo OK/FALHA.

Pré-requisito: o chamador limpou/preparou demo:counter:*.
Uso: python counter.py [target]   (default: localhost:18080)
"""
import sys

import client  # define sys.path p/ os stubs e expõe connect()
import grpc
import string_pb2

KEY = "demo:counter:n"
TEXT = "demo:counter:text"


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

    # Estado inicial = 0 (via SET, pois ainda não há DEL).
    stub.Set(string_pb2.SetRequest(key=KEY, value=b"0"))

    r = stub.Incr(string_pb2.IncrRequest(key=KEY))
    print(f"--> INCR {KEY}\n<-- value={r.value}")
    check(r.value == 1, "INCR -> 1")

    r = stub.IncrBy(string_pb2.IncrByRequest(key=KEY, increment=10))
    print(f"--> INCRBY {KEY} 10\n<-- value={r.value}")
    check(r.value == 11, "INCRBY +10 -> 11")

    r = stub.IncrBy(string_pb2.IncrByRequest(key=KEY, increment=-4))
    print(f"--> INCRBY {KEY} -4\n<-- value={r.value}")
    check(r.value == 7, "INCRBY -4 -> 7")

    # Erro: valor não-inteiro -> FAILED_PRECONDITION.
    stub.Set(string_pb2.SetRequest(key=TEXT, value=b"abc"))
    print(f"--> INCR {TEXT} (valor 'abc')")
    try:
        stub.Incr(string_pb2.IncrRequest(key=TEXT))
        check(False, "esperava erro, veio sucesso")
    except grpc.RpcError as err:
        print(f"<-- ERROR status={err.code().name}")
        check(err.code() == grpc.StatusCode.FAILED_PRECONDITION,
              "não-inteiro -> FAILED_PRECONDITION")

    channel.close()

    print()
    if failures:
        print(f"{failures} verificação(ões) falharam")
        sys.exit(1)
    print("INCR/INCRBY smoke OK")


if __name__ == "__main__":
    main()
