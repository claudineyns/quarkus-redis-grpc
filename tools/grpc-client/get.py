"""Cliente GET interativo (didático).

Mostra o fluxo de cada chamada gRPC GET: a REQUEST enviada e a RESPONSE
recebida (ou o ERROR, quando o status não é OK). Uso interativo: digite uma
chave e Enter; 'quit'/'exit' (ou Ctrl-D) para sair. Também aceita entrada via
pipe (uma chave por linha).

Uso: python get.py [target]   (default: localhost:18080)
"""
import sys

import client  # define sys.path p/ os stubs e expõe connect()
import grpc
import string_pb2


def render_value(raw):
    """Mostra o valor (bytes) de forma legível: como texto se for UTF-8 válido,
    senão como contagem de bytes (o valor é binary-safe)."""
    try:
        return repr(raw.decode("utf-8"))
    except UnicodeDecodeError:
        return f"<{len(raw)} bytes>"


def do_get(stub, key):
    print(f"  --> REQUEST   StringService/Get  GetRequest(key={key!r})")
    try:
        response = stub.Get(string_pb2.GetRequest(key=key))
        if response.HasField("value"):
            print(f"  <-- RESPONSE  found=true  value={render_value(response.value)}")
        else:
            print("  <-- RESPONSE  found=false (nil — chave inexistente; sucesso)")
    except grpc.RpcError as err:
        print(f"  <-- ERROR     status={err.code().name}  details={err.details()!r}")


def main():
    target = sys.argv[1] if len(sys.argv) > 1 else "localhost:18080"
    channel, stub = client.connect(target)
    print(f"Conectado a {target} (StringService).")
    print("Digite uma chave e Enter para fazer GET; 'quit' para sair.\n")

    try:
        while True:
            try:
                key = input("key> ").strip()
            except EOFError:
                break
            if not key:
                continue
            if key.lower() in ("quit", "exit"):
                break
            do_get(stub, key)
            print()
    finally:
        channel.close()
        print("conexão encerrada.")


if __name__ == "__main__":
    main()
