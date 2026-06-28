"""Demo/validação do SetService — fatia 1.

Exercita SADD/SREM/SCARD/SISMEMBER/SMISMEMBER/SMEMBERS. O próprio cliente semeia
(SADD) e limpa (SREM) o estado. Resumo OK/FALHA.

Uso: python sets.py [target]   (default: localhost:18080)
"""
import sys

import client  # garante generated/ no sys.path e expõe make_channel (TLS+auth)
import set_pb2
import set_pb2_grpc

KEY = "demo:set:s"


def main():
    target = sys.argv[1] if len(sys.argv) > 1 else "localhost:18080"
    channel = client.make_channel(target)  # TLS se REDIS_GRPC_CA; envia credenciais
    stub = set_pb2_grpc.SetServiceStub(channel)
    print(f"Conectado a {target} (SetService).\n")

    failures = 0

    def check(condition, msg):
        nonlocal failures
        print(f"    {'OK   ' if condition else 'FALHA'} {msg}")
        if not condition:
            failures += 1

    # limpa execuções anteriores
    stub.SRem(set_pb2.SRemRequest(key=KEY, members=["a", "b", "c", "d"]))

    # SADD com duplicata interna ("a") → conta distintos.
    added = stub.SAdd(set_pb2.SAddRequest(key=KEY, members=["a", "b", "a", "c"])).count
    print(f"--> SADD {KEY} a b a c\n<-- added={added}")
    check(added == 3, "SADD conta distintos (3)")

    card = stub.SCard(set_pb2.SCardRequest(key=KEY)).count
    print(f"--> SCARD {KEY}\n<-- count={card}")
    check(card == 3, "SCARD == 3")

    check(stub.SIsMember(set_pb2.SIsMemberRequest(key=KEY, member="a")).is_member,
          "SISMEMBER a -> true")
    check(not stub.SIsMember(set_pb2.SIsMemberRequest(key=KEY, member="z")).is_member,
          "SISMEMBER z -> false")

    mism = list(stub.SMIsMember(set_pb2.SMIsMemberRequest(key=KEY, members=["a", "z", "c"])).members)
    print(f"--> SMISMEMBER {KEY} a z c\n<-- {mism}")
    check(mism == [True, False, True], "SMISMEMBER alinhado [T,F,T]")

    removed = stub.SRem(set_pb2.SRemRequest(key=KEY, members=["a", "z"])).count
    print(f"--> SREM {KEY} a z\n<-- removed={removed}")
    check(removed == 1, "SREM remove apenas existentes (1)")

    members = sorted(stub.SMembers(set_pb2.SMembersRequest(key=KEY)).members)
    print(f"--> SMEMBERS {KEY}\n<-- {members}")
    check(members == ["b", "c"], "SMEMBERS == [b, c]")

    channel.close()

    print()
    if failures:
        print(f"{failures} verificacao(oes) falharam")
        sys.exit(1)
    print("SetService (fatia 1) smoke OK")


if __name__ == "__main__":
    main()
