"""Demo/validação do SetService — fatia 2 (SPOP).

Semeia 5 membros via SADD e exercita SPOP sem count (1 membro), com count
(até N) e count > cardinalidade (esvazia), conferindo com SCARD. Resumo OK/FALHA.

Uso: python setpop.py [target]   (default: localhost:18080)
"""
import sys

import client  # garante generated/ no sys.path e expõe make_channel (TLS+auth)
import set_pb2
import set_pb2_grpc

KEY = "demo:set:pop"


def main():
    target = sys.argv[1] if len(sys.argv) > 1 else "localhost:18080"
    channel = client.make_channel(target)
    stub = set_pb2_grpc.SetServiceStub(channel)
    print(f"Conectado a {target} (SetService).\n")

    failures = 0

    def check(condition, msg):
        nonlocal failures
        print(f"    {'OK   ' if condition else 'FALHA'} {msg}")
        if not condition:
            failures += 1

    def card():
        return stub.SCard(set_pb2.SCardRequest(key=KEY)).count

    # estado inicial: 5 membros
    stub.SRem(set_pb2.SRemRequest(key=KEY, members=["a", "b", "c", "d", "e"]))
    stub.SAdd(set_pb2.SAddRequest(key=KEY, members=["a", "b", "c", "d", "e"]))

    one = list(stub.SPop(set_pb2.SPopRequest(key=KEY)).members)
    print(f"--> SPOP {KEY}\n<-- {one}")
    check(len(one) == 1, "SPOP sem count -> 1 membro")
    check(card() == 4, "SCARD == 4 apos pop")

    two = list(stub.SPop(set_pb2.SPopRequest(key=KEY, count=2)).members)
    print(f"--> SPOP {KEY} 2\n<-- {two}")
    check(len(two) == 2, "SPOP count=2 -> 2 membros")
    check(card() == 2, "SCARD == 2")

    rest = list(stub.SPop(set_pb2.SPopRequest(key=KEY, count=10)).members)
    print(f"--> SPOP {KEY} 10\n<-- {rest}")
    check(len(rest) == 2, "SPOP count>card -> retorna restantes (2)")
    check(card() == 0, "SCARD == 0 (vazio)")

    empty = list(stub.SPop(set_pb2.SPopRequest(key=KEY)).members)
    print(f"--> SPOP {KEY} (ausente)\n<-- {empty}")
    check(len(empty) == 0, "SPOP em chave ausente -> vazio")

    channel.close()

    print()
    if failures:
        print(f"{failures} verificacao(oes) falharam")
        sys.exit(1)
    print("SetService (fatia 2 / SPOP) smoke OK")


if __name__ == "__main__":
    main()
