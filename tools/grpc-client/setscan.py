"""Demo/validação do SetService — fatia 3 (SSCAN).

Itera o SSCAN do cursor "0" até voltar "0" sobre um set, medindo nº de páginas e
tempo total. Membros deduplicados num set (SSCAN pode REPETIR). Compara COUNT
default × COUNT=100. Resumo OK/FALHA.

Pré-requisito: o chamador semeou `expected` membros em `key` (ver
99e-smoke-setscan.sh). Uso: python setscan.py [target] [key] [expected]
"""
import sys
import time

import client  # garante generated/ no sys.path e expõe make_channel (TLS+auth)
import set_pb2
import set_pb2_grpc


def scan_all(stub, key, count):
    """Itera o SSCAN até o cursor voltar a "0".

    Retorna (membros_distintos, num_paginas, segundos). COUNT é apenas uma DICA.
    """
    found = set()
    pages = 0
    cursor = "0"
    start = time.perf_counter()
    while True:
        request = set_pb2.SScanRequest(key=key, cursor=cursor)
        if count is not None:
            request.count = count
        response = stub.SScan(request)
        pages += 1
        found.update(response.members)
        cursor = response.cursor
        if cursor == "0":
            break
    return found, pages, time.perf_counter() - start


def main():
    target = sys.argv[1] if len(sys.argv) > 1 else "localhost:18080"
    key = sys.argv[2] if len(sys.argv) > 2 else "demo:set:scan"
    expected = int(sys.argv[3]) if len(sys.argv) > 3 else 1000

    channel = client.make_channel(target)  # TLS se REDIS_GRPC_CA; envia credenciais
    stub = set_pb2_grpc.SetServiceStub(channel)
    print(f"Conectado a {target} (SetService).\n")

    failures = 0

    def check(condition, msg):
        nonlocal failures
        print(f"    {'OK   ' if condition else 'FALHA'} {msg}")
        if not condition:
            failures += 1

    for count in (None, 100):
        label = "default" if count is None else str(count)
        found, pages, secs = scan_all(stub, key, count)
        print(f"--> SSCAN {key} COUNT={label}")
        print(f"<-- {len(found)} membros distintos em {pages} pagina(s), {secs * 1000:.1f} ms")
        check(len(found) == expected,
              f"COUNT={label}: {len(found)} == {expected} membros distintos")

    print("\n(nota: 1000 membros e carga leve — valida iteracao multi-pagina e da um "
          "baseline de latencia; e o caso em que SMEMBERS seria arriscado)")

    channel.close()

    print()
    if failures:
        print(f"{failures} verificacao(oes) falharam")
        sys.exit(1)
    print("SetService (fatia 3 / SSCAN) smoke OK")


if __name__ == "__main__":
    main()
