"""Cliente gRPC (didático) para o proxy redis-grpc — família StringService.

Conceitos de gRPC no lado cliente (Python):
- channel: a conexão HTTP/2 com o servidor. Usamos insecure_channel (texto
  claro) porque a borda TLS ainda não foi implementada no proxy.
- stub: o "proxy local" gerado a partir do .proto; chamar stub.Get(...) dispara
  a chamada remota como se fosse um método local.
- mensagens: GetRequest/GetResponse, geradas pelo protoc (módulo string_pb2).
- status: respostas não-OK chegam como grpc.RpcError, com .code()
  (grpc.StatusCode) e .details() (no nosso caso, a mensagem crua do Redis).
"""
import os
import sys

# Os stubs gerados ficam em ./generated; coloca essa pasta no sys.path para que
# "import string_pb2" (e o import interno do string_pb2_grpc) funcionem.
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "generated"))

import grpc  # noqa: E402
import string_pb2  # noqa: E402
import string_pb2_grpc  # noqa: E402


def make_channel(target):
    """Cria um channel para o alvo. Se a variável de ambiente REDIS_GRPC_CA
    apontar para um CA (PEM), usa TLS (secure channel) confiando nesse CA;
    caso contrário, usa texto claro (insecure)."""
    ca_path = os.environ.get("REDIS_GRPC_CA")
    if not ca_path:
        return grpc.insecure_channel(target)
    with open(ca_path, "rb") as handle:
        creds = grpc.ssl_channel_credentials(root_certificates=handle.read())
    return grpc.secure_channel(target, creds)


def connect(target):
    """Cria um channel (TLS se REDIS_GRPC_CA estiver definido) e o stub do
    StringService. Retorna (channel, stub). Lembre de fechar o channel ao final.
    """
    channel = make_channel(target)
    stub = string_pb2_grpc.StringServiceStub(channel)
    return channel, stub


def get(stub, key):
    """Executa GET e devolve (found, value_bytes).

    O campo 'value' do GetResponse é optional: HasField('value') distingue
    nil (chave ausente → found=False) de string vazia (presente, porém vazia)
    — espelhando o nil do Redis. Um erro real (ex.: WRONGTYPE) sobe como
    grpc.RpcError e NÃO é tratado aqui (quem chama decide).
    """
    response = stub.Get(string_pb2.GetRequest(key=key))
    if response.HasField("value"):
        return True, response.value
    return False, None
