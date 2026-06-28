"""Demo/validação do HashService — fatia 2 (HMGET/HGETALL/HKEYS/HVALS).

Semeia 3 campos via HSET e exercita HMGET (alinhado, com nil), HGETALL (pares),
HKEYS e HVALS. Resumo OK/FALHA.

Uso: python hashlist.py [target]   (default: localhost:18080)
"""
import sys

import client  # garante generated/ no sys.path e expõe make_channel (TLS+auth)
import hash_pb2
import hash_pb2_grpc

KEY = "demo:hash:l"


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

    # estado: a=1, b=2, c=3
    stub.HDel(hash_pb2.HDelRequest(key=KEY, fields=["a", "b", "c"]))
    stub.HSet(hash_pb2.HSetRequest(key=KEY, fields=[fv("a", "1"), fv("b", "2"), fv("c", "3")]))

    mget = stub.HMGet(hash_pb2.HMGetRequest(key=KEY, fields=["a", "x", "c"]))
    vals = [(v.value.decode("utf-8") if v.HasField("value") else None) for v in mget.values]
    print(f"--> HMGET {KEY} a x c\n<-- {vals}")
    check(vals == ["1", None, "3"], "HMGET alinhado -> [1, None, 3]")

    entries = {e.field: e.value.decode("utf-8")
               for e in stub.HGetAll(hash_pb2.HGetAllRequest(key=KEY)).entries}
    print(f"--> HGETALL {KEY}\n<-- {entries}")
    check(entries == {"a": "1", "b": "2", "c": "3"}, "HGETALL -> {a:1, b:2, c:3}")

    keys = sorted(stub.HKeys(hash_pb2.HKeysRequest(key=KEY)).fields)
    print(f"--> HKEYS {KEY}\n<-- {keys}")
    check(keys == ["a", "b", "c"], "HKEYS -> [a, b, c]")

    hvals = sorted(v.decode("utf-8") for v in stub.HVals(hash_pb2.HValsRequest(key=KEY)).values)
    print(f"--> HVALS {KEY}\n<-- {hvals}")
    check(hvals == ["1", "2", "3"], "HVALS -> [1, 2, 3]")

    channel.close()

    print()
    if failures:
        print(f"{failures} verificacao(oes) falharam")
        sys.exit(1)
    print("HashService (fatia 2) smoke OK")


if __name__ == "__main__":
    main()
