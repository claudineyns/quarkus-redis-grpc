"""Demo/validação do HashService — fatia 1.

Exercita HSET (multi-field, novos × atualização), HGET (binary-safe), HEXISTS,
HLEN e HDEL. O próprio cliente semeia e limpa o estado. Resumo OK/FALHA.

Uso: python hashes.py [target]   (default: localhost:18080)
"""
import sys

import client  # garante generated/ no sys.path e expõe make_channel (TLS+auth)
import hash_pb2
import hash_pb2_grpc

KEY = "demo:hash:h"


def fv(field, value):
    return hash_pb2.FieldValue(field=field, value=value.encode("utf-8"))


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

    # limpa execuções anteriores
    stub.HDel(hash_pb2.HDelRequest(key=KEY, fields=["f1", "f2", "f3"]))

    created = stub.HSet(hash_pb2.HSetRequest(key=KEY, fields=[fv("f1", "v1"), fv("f2", "v2")])).count
    print(f"--> HSET {KEY} f1 v1 f2 v2\n<-- count={created}")
    check(created == 2, "HSET cria 2 campos novos")

    mixed = stub.HSet(hash_pb2.HSetRequest(key=KEY, fields=[fv("f1", "v1b"), fv("f3", "v3")])).count
    print(f"--> HSET {KEY} f1 v1b f3 v3\n<-- count={mixed}")
    check(mixed == 1, "HSET atualiza f1 + novo f3 -> 1 novo")

    g = stub.HGet(hash_pb2.HGetRequest(key=KEY, field="f1"))
    print(f"--> HGET {KEY} f1\n<-- {g.value!r}")
    check(g.HasField("value") and g.value.decode("utf-8") == "v1b", "HGET f1 == v1b")

    absent = stub.HGet(hash_pb2.HGetRequest(key=KEY, field="nope"))
    check(not absent.HasField("value"), "HGET campo ausente -> sem value")

    check(stub.HExists(hash_pb2.HExistsRequest(key=KEY, field="f2")).exists, "HEXISTS f2 -> true")
    check(not stub.HExists(hash_pb2.HExistsRequest(key=KEY, field="z")).exists, "HEXISTS z -> false")

    check(stub.HLen(hash_pb2.HLenRequest(key=KEY)).count == 3, "HLEN == 3")

    removed = stub.HDel(hash_pb2.HDelRequest(key=KEY, fields=["f1", "z"])).count
    print(f"--> HDEL {KEY} f1 z\n<-- removed={removed}")
    check(removed == 1, "HDEL remove apenas existentes (1)")
    check(stub.HLen(hash_pb2.HLenRequest(key=KEY)).count == 2, "HLEN == 2 apos del")

    channel.close()

    print()
    if failures:
        print(f"{failures} verificacao(oes) falharam")
        sys.exit(1)
    print("HashService (fatia 1) smoke OK")


if __name__ == "__main__":
    main()
