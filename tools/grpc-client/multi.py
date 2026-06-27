"""Demo/validação do MSET + MGET contra o proxy.

MSET grava vários pares; MGET lê de volta na ordem das chaves, com nil para
chave ausente. Imprime o fluxo e um resumo OK/FALHA.

Pré-requisito: o chamador (infra/ocp/92-smoke-multi.sh) limpou demo:multi:*.
Uso: python multi.py [target]   (default: localhost:18080)
"""
import sys

import client  # define sys.path p/ os stubs e expõe connect()
import string_pb2

A = "demo:multi:a"
B = "demo:multi:b"
C = "demo:multi:c"
MISSING = "demo:multi:missing"


def kv(key, value):
    return string_pb2.KeyValue(key=key, value=value.encode("utf-8"))


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

    # MSET de 3 pares.
    print(f"--> MSET {A}=1 {B}=2 {C}=3")
    stub.MSet(string_pb2.MSetRequest(entries=[kv(A, "1"), kv(B, "2"), kv(C, "3")]))
    print("<-- OK (MSetResponse vazia)\n")

    # MGET incluindo uma chave ausente (testa nil + ordem).
    print(f"--> MGET {A} {MISSING} {C}")
    response = stub.MGet(string_pb2.MGetRequest(keys=[A, MISSING, C]))
    rendered = [
        repr(v.value.decode("utf-8")) if v.HasField("value") else "nil"
        for v in response.values
    ]
    print(f"<-- values=[{', '.join(rendered)}]")

    check(len(response.values) == 3, "3 valores devolvidos")
    check(response.values[0].HasField("value")
          and response.values[0].value.decode() == "1", f"{A} == '1'")
    check(not response.values[1].HasField("value"), f"{MISSING} == nil")
    check(response.values[2].HasField("value")
          and response.values[2].value.decode() == "3", f"{C} == '3'")

    channel.close()

    print()
    if failures:
        print(f"{failures} verificação(ões) falharam")
        sys.exit(1)
    print("MSET/MGET smoke OK")


if __name__ == "__main__":
    main()
