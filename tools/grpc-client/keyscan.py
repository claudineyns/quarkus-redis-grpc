"""Demo/validação do KeyService — fatia 3 (SCAN).

Itera o SCAN do cursor "0" até voltar "0", medindo nº de páginas e tempo total.
As chaves são deduplicadas num set (o SCAN pode REPETIR chaves entre páginas).
Compara COUNT default × COUNT=100 para evidenciar o impacto de round-trips
(COUNT maior = menos páginas). Resumo OK/FALHA.

Pré-requisito: o chamador semeou `expected` chaves sob o padrão `match`
(ver 98b-smoke-keyscan.sh). Uso: python keyscan.py [target] [match] [expected]
"""
import sys
import time

import client  # garante generated/ no sys.path e expõe make_channel (TLS+auth)
import key_pb2
import key_pb2_grpc


def scan_all(stub, match, count):
    """Itera o SCAN até o cursor voltar a "0".

    Retorna (chaves_distintas, num_paginas, segundos). COUNT é apenas uma DICA:
    o Redis decide o tamanho real de cada página; por isso iteramos até "0".
    """
    found = set()
    pages = 0
    cursor = "0"
    start = time.perf_counter()
    while True:
        request = key_pb2.ScanRequest(cursor=cursor, match=match)
        if count is not None:
            request.count = count
        response = stub.Scan(request)
        pages += 1
        found.update(response.keys)
        cursor = response.cursor
        if cursor == "0":
            break
    return found, pages, time.perf_counter() - start


def main():
    target = sys.argv[1] if len(sys.argv) > 1 else "localhost:18080"
    match = sys.argv[2] if len(sys.argv) > 2 else "scan:perf:*"
    expected = int(sys.argv[3]) if len(sys.argv) > 3 else 1000

    channel = client.make_channel(target)  # TLS se REDIS_GRPC_CA; envia credenciais
    stub = key_pb2_grpc.KeyServiceStub(channel)
    print(f"Conectado a {target} (KeyService).\n")

    failures = 0

    def check(condition, msg):
        nonlocal failures
        print(f"    {'OK   ' if condition else 'FALHA'} {msg}")
        if not condition:
            failures += 1

    for count in (None, 100):
        label = "default" if count is None else str(count)
        found, pages, secs = scan_all(stub, match, count)
        print(f"--> SCAN match={match} COUNT={label}")
        print(f"<-- {len(found)} chaves distintas em {pages} pagina(s), {secs * 1000:.1f} ms")
        check(len(found) == expected,
              f"COUNT={label}: {len(found)} == {expected} chaves distintas")

    print("\n(nota: 1000 chaves e carga leve — valida iteracao multi-pagina e da um "
          "baseline de latencia, nao e teste de stress)")

    channel.close()

    print()
    if failures:
        print(f"{failures} verificacao(oes) falharam")
        sys.exit(1)
    print("KeyService (fatia 3 / SCAN) smoke OK")


if __name__ == "__main__":
    main()
