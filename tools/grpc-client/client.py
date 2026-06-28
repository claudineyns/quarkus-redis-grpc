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
import collections
import os
import sys

# Os stubs gerados ficam em ./generated; coloca essa pasta no sys.path para que
# "import string_pb2" (e o import interno do string_pb2_grpc) funcionem.
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "generated"))

import grpc  # noqa: E402
import string_pb2  # noqa: E402
import string_pb2_grpc  # noqa: E402

# Credencial FIXA de laboratório: o SHA-256(access) está na allowlist do ConfigMap
# e o secret = HMAC(chave_mestra_lab, access). Sobreponível por env
# REDIS_GRPC_ACCESS_KEY / REDIS_GRPC_SECRET_KEY (defina vazio para OMITIR).
LAB_ACCESS_KEY = "b2e9cd5d8e2963ee4b5ca4e038912833"
LAB_SECRET_KEY = "710ab7031fc33d6caf89dc9a38b684177bf70bc8b18d926cea8967af0cc8233b"


class _ClientCallDetails(
        collections.namedtuple(
            "_ClientCallDetails",
            ("method", "timeout", "metadata", "credentials",
             "wait_for_ready", "compression")),
        grpc.ClientCallDetails):
    pass


class _CredentialInterceptor(grpc.UnaryUnaryClientInterceptor):
    """Adiciona os headers de credenciais (x-grpc-access-key/x-grpc-secret-key)
    a cada chamada unária — espelha o que o AuthInterceptor do proxy valida."""

    def __init__(self, access_key, secret_key):
        self._creds = [
            ("x-grpc-access-key", access_key),
            ("x-grpc-secret-key", secret_key),
        ]

    def intercept_unary_unary(self, continuation, details, request):
        metadata = list(details.metadata or [])
        metadata.extend(self._creds)
        new_details = _ClientCallDetails(
            details.method, details.timeout, metadata,
            details.credentials, details.wait_for_ready, details.compression)
        return continuation(new_details, request)


def make_channel(target):
    """Cria um channel para o alvo.
    - REDIS_GRPC_CA (PEM) → TLS (secure channel) confiando nesse CA; senão texto claro.
    - REDIS_GRPC_ACCESS_KEY + REDIS_GRPC_SECRET_KEY → injeta as credenciais na metadata.
    """
    ca_path = os.environ.get("REDIS_GRPC_CA")
    if ca_path:
        with open(ca_path, "rb") as handle:
            creds = grpc.ssl_channel_credentials(root_certificates=handle.read())
        channel = grpc.secure_channel(target, creds)
    else:
        channel = grpc.insecure_channel(target)

    access_key = os.environ.get("REDIS_GRPC_ACCESS_KEY", LAB_ACCESS_KEY)
    secret_key = os.environ.get("REDIS_GRPC_SECRET_KEY", LAB_SECRET_KEY)
    if access_key and secret_key:
        channel = grpc.intercept_channel(channel, _CredentialInterceptor(access_key, secret_key))
    return channel


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
