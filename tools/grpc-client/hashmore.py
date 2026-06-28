"""Demo/validação do HashService — fatia 3 (HSETNX/HINCRBY/HSCAN).

HSETNX (cria × já existe), HINCRBY (acumula) e HSCAN iterando 1000 campos
(multi-página, métricas páginas/tempo, COUNT default × 100). Resumo OK/FALHA.

Pré-requisito: o chamador semeou 1000 campos em demo:hash:scan (ver
99h-smoke-hashscan.sh). Uso: python hashmore.py [target]
"""
import sys
import time

import client  # garante generated/ no sys.path e expõe make_channel (TLS+auth)
import hash_pb2
import hash_pb2_grpc

KEY = "demo:hash:m"
SCAN_KEY = "demo:hash:scan"


def scan_all(stub, key, count):
    """Itera o HSCAN até o cursor voltar a "0". Retorna (campos, paginas, segundos)."""
    found = {}
    pages = 0
    cursor = "0"
    start = time.perf_counter()
    while True:
        request = hash_pb2.HScanRequest(key=key, cursor=cursor)
        if count is not None:
            request.count = count
        response = stub.HScan(request)
        pages += 1
        for e in response.entries:
            found[e.field] = e.value
        cursor = response.cursor
        if cursor == "0":
            break
    return found, pages, time.perf_counter() - start


def main():
    target = sys.argv[1] if len(sys.argv) > 1 else "localhost:18080"
    channel = client.make_channel(target)
    stub = hash_pb2_grpc.HashServiceStub(channel)
    print(f"Conectado a {target} (HashService).\n")

    failures = 0

    def check(condition, msg):
        nonlocal failures
        print(f"    {'OK   ' if condition else 'FALHA'} {msg}")
        if not condition:
            failures += 1

    stub.HDel(hash_pb2.HDelRequest(key=KEY, fields=["f", "n"]))

    created = stub.HSetNx(hash_pb2.HSetNxRequest(key=KEY, field="f", value=b"v1")).applied
    print(f"--> HSETNX {KEY} f v1\n<-- applied={created}")
    check(created, "HSETNX cria -> applied")
    again = stub.HSetNx(hash_pb2.HSetNxRequest(key=KEY, field="f", value=b"v2")).applied
    print(f"--> HSETNX {KEY} f v2\n<-- applied={again}")
    check(not again, "HSETNX campo existente -> not applied")

    v1 = stub.HIncrBy(hash_pb2.HIncrByRequest(key=KEY, field="n", increment=5)).value
    v2 = stub.HIncrBy(hash_pb2.HIncrByRequest(key=KEY, field="n", increment=2)).value
    print(f"--> HINCRBY {KEY} n 5; n 2\n<-- {v1}; {v2}")
    check(v1 == 5 and v2 == 7, "HINCRBY 5 depois 2 -> 7")

    for count in (None, 100):
        label = "default" if count is None else str(count)
        found, pages, secs = scan_all(stub, SCAN_KEY, count)
        print(f"--> HSCAN {SCAN_KEY} COUNT={label}")
        print(f"<-- {len(found)} campos distintos em {pages} pagina(s), {secs * 1000:.1f} ms")
        check(len(found) == 1000, f"COUNT={label}: {len(found)} == 1000 campos distintos")

    channel.close()

    print()
    if failures:
        print(f"{failures} verificacao(oes) falharam")
        sys.exit(1)
    print("HashService (fatia 3) smoke OK")


if __name__ == "__main__":
    main()
