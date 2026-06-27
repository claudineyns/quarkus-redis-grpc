"""Smoke test do GET contra o proxy implantado — sucesso e erro.

Pré-requisito: o chamador (infra/ocp/90-smoke-test.sh) já semeou no Redis:
  - PRESENT_KEY com PRESENT_VALUE (string)
  - WRONGTYPE_KEY como hash (para provocar WRONGTYPE no GET)
  - ABSENT_KEY não existe (testa o caminho nil)

Uso: python smoke.py [target]   (default: localhost:18080)
"""
import sys

import grpc

import client

PRESENT_KEY = "smoke:greeting"
PRESENT_VALUE = b"hello-from-crc"
ABSENT_KEY = "smoke:absent"
WRONGTYPE_KEY = "smoke:hash"


def main():
    target = sys.argv[1] if len(sys.argv) > 1 else "localhost:18080"
    channel, stub = client.connect(target)
    failures = 0

    # 1) Sucesso: chave presente → value presente e igual ao semeado.
    found, value = client.get(stub, PRESENT_KEY)
    if found and value == PRESENT_VALUE:
        print(f"OK    GET {PRESENT_KEY!r} -> {value!r}")
    else:
        print(f"FALHA GET {PRESENT_KEY!r}: found={found} value={value!r}")
        failures += 1

    # 2) Sucesso: chave ausente → nil (found=False). Cache miss NÃO é erro.
    found, value = client.get(stub, ABSENT_KEY)
    if not found:
        print(f"OK    GET {ABSENT_KEY!r} -> nil (ausente)")
    else:
        print(f"FALHA GET {ABSENT_KEY!r}: esperava nil, veio {value!r}")
        failures += 1

    # 3) Erro: chave de outro tipo → status FAILED_PRECONDITION (WRONGTYPE).
    try:
        client.get(stub, WRONGTYPE_KEY)
        print(f"FALHA GET {WRONGTYPE_KEY!r}: esperava erro, veio sucesso")
        failures += 1
    except grpc.RpcError as err:
        if err.code() == grpc.StatusCode.FAILED_PRECONDITION:
            print(f"OK    GET {WRONGTYPE_KEY!r} -> {err.code().name}: {err.details()}")
        else:
            print(f"FALHA GET {WRONGTYPE_KEY!r}: status inesperado "
                  f"{err.code().name}: {err.details()}")
            failures += 1

    channel.close()

    if failures:
        print(f"\n{failures} verificação(ões) falharam")
        sys.exit(1)
    print("\nsmoke test OK")


if __name__ == "__main__":
    main()
