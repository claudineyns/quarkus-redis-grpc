"""Demo/validação do SET contra o proxy — mostra o fluxo e verifica o efeito.

Para cada cenário imprime a REQUEST (opções) e a RESPONSE (applied/previous), e
confirma o resultado com um GET. Ao final, um resumo OK/FALHA.

Pré-requisito: o chamador (infra/ocp/91-smoke-set.sh) limpou as chaves demo:set:*
para um estado inicial previsível.

Uso: python set.py [target]   (default: localhost:18080)
"""
import sys

import client  # define sys.path p/ os stubs e expõe connect()/get()
import string_pb2

KEY_A = "demo:set:a"      # cenários de chave existente
KEY_B = "demo:set:b"      # NX em chave ausente
KEY_C = "demo:set:c"      # XX em chave ausente
KEY_TTL = "demo:set:ttl"  # expiração (TTL conferido no script bash)


def set_req(key, value, *, condition=None, get=False, ex_seconds=None):
    """Monta um SetRequest. value vai como bytes (binary-safe)."""
    kwargs = {"key": key, "value": value.encode("utf-8"), "get": get}
    if condition is not None:
        kwargs["condition"] = condition
    if ex_seconds is not None:
        kwargs["ex_seconds"] = ex_seconds
    return string_pb2.SetRequest(**kwargs)


def main():
    target = sys.argv[1] if len(sys.argv) > 1 else "localhost:18080"
    channel, stub = client.connect(target)
    print(f"Conectado a {target} (StringService).\n")

    failures = 0

    def run(desc, req):
        resp = stub.Set(req)
        prev = resp.previous.decode("utf-8") if resp.HasField("previous") else None
        print(f"--> SET {desc}")
        print(f"<-- applied={resp.applied}  previous={prev!r}")
        return resp

    def check(condition, msg):
        nonlocal failures
        print(f"    {'OK   ' if condition else 'FALHA'} {msg}")
        if not condition:
            failures += 1

    def value_of(key):
        found, raw = client.get(stub, key)
        return raw.decode("utf-8") if found else None

    try:
        # 1) SET incondicional → grava.
        run(f"{KEY_A}=v1", set_req(KEY_A, "v1"))
        check(value_of(KEY_A) == "v1", f"{KEY_A} == 'v1'")

        # 2) NX em chave existente → NÃO grava.
        r = run(f"{KEY_A}=v2 NX", set_req(KEY_A, "v2", condition=string_pb2.SET_CONDITION_NX))
        check(r.applied is False, "applied=false (NX barrou)")
        check(value_of(KEY_A) == "v1", f"{KEY_A} intacto == 'v1'")

        # 3) XX em chave existente → grava.
        r = run(f"{KEY_A}=v3 XX", set_req(KEY_A, "v3", condition=string_pb2.SET_CONDITION_XX))
        check(r.applied is True, "applied=true (XX aplicou)")
        check(value_of(KEY_A) == "v3", f"{KEY_A} == 'v3'")

        # 4) GET → devolve o valor antigo e grava o novo.
        r = run(f"{KEY_A}=v4 GET", set_req(KEY_A, "v4", get=True))
        check(r.HasField("previous") and r.previous.decode() == "v3", "previous == 'v3'")
        check(value_of(KEY_A) == "v4", f"{KEY_A} == 'v4'")

        # 5) NX em chave ausente → grava.
        r = run(f"{KEY_B}=first NX", set_req(KEY_B, "first", condition=string_pb2.SET_CONDITION_NX))
        check(r.applied is True, "applied=true (NX em ausente)")
        check(value_of(KEY_B) == "first", f"{KEY_B} == 'first'")

        # 6) XX em chave ausente → NÃO grava.
        r = run(f"{KEY_C}=x XX", set_req(KEY_C, "x", condition=string_pb2.SET_CONDITION_XX))
        check(r.applied is False, "applied=false (XX em ausente)")
        check(value_of(KEY_C) is None, f"{KEY_C} ausente")

        # 7) Expiração EX → grava com TTL (TTL conferido no script bash).
        r = run(f"{KEY_TTL}=t EX=100", set_req(KEY_TTL, "t", ex_seconds=100))
        check(r.applied is True, "applied=true (com EX)")
    finally:
        channel.close()

    print()
    if failures:
        print(f"{failures} verificação(ões) falharam")
        sys.exit(1)
    print("SET smoke OK")


if __name__ == "__main__":
    main()
