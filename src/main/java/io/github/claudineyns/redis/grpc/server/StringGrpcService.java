package io.github.claudineyns.redis.grpc.server;

import org.jboss.logging.Logger;
import org.jboss.logging.MDC;

import com.google.protobuf.ByteString;

import io.github.claudineyns.redis.grpc.v1.GetRequest;
import io.github.claudineyns.redis.grpc.v1.GetResponse;
import io.github.claudineyns.redis.grpc.v1.SetRequest;
import io.github.claudineyns.redis.grpc.v1.SetResponse;
import io.github.claudineyns.redis.grpc.v1.StringService;
import io.grpc.Status;
import io.quarkus.grpc.GrpcService;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.redis.client.Redis;
import io.vertx.mutiny.redis.client.Request;
import io.vertx.mutiny.redis.client.Response;
import io.vertx.mutiny.redis.client.Command;
import jakarta.inject.Inject;

/**
 * Família KEY/VALUE — implementação gRPC do {@code StringService}.
 *
 * <p>Tradução 1:1 de comandos Redis (DESIGN seção 2). Fatia atual: GET.
 */
@GrpcService
public class StringGrpcService implements StringService {

    private static final Logger LOG = Logger.getLogger(StringGrpcService.class);

    private static final String CMD_GET = "GET";

    @Inject
    Redis redis; // CDI: não pode ser final (exceção da convenção de DESIGN seção 10)

    @Override
    public Uni<GetResponse> get(final GetRequest request) {
        // Metadados desta operação no MDC (propagam pela cadeia Mutiny e saem nas
        // linhas de log). Valores nunca vão ao log; a chave, sim (DESIGN 8.1).
        MDC.put(LogFields.COMMAND, CMD_GET);
        MDC.put(LogFields.KEY, request.getKey());
        LOG.debug("GET recebido");

        // Monta o comando Redis "GET <key>" como Request de baixo nível. Usamos
        // Redis + Request (e não a RedisAPI tipada como String) para preservar o
        // binary-safe dos valores — ver DESIGN seção 4.
        final Request command = Request.cmd(Command.GET).arg(request.getKey());
        final long startNanos = System.nanoTime();

        // redis.send devolve um Uni<Response> (não-bloqueante). Encadeamos:
        //  - map: mede a latência do Redis, traduz a Response para o GetResponse
        //    e loga a conclusão;
        //  - onFailure: um erro RESP (ex.: WRONGTYPE) ou falha de infra é
        //    convertido em status gRPC. Resultado normal — inclusive nil — NÃO
        //    passa por aqui; nil é sucesso e sai no payload (DESIGN seção 5.1).
        return redis.send(command)
                .map(response -> {
                    MDC.put(LogFields.REDIS_DURATION_MS,
                            Long.toString((System.nanoTime() - startNanos) / 1_000_000L));
                    final GetResponse result = toGetResponse(response);
                    LOG.debugf("GET concluído (found=%s)", result.hasValue());
                    return result;
                })
                .onFailure().transform(RedisErrors::toStatus);
    }

    @Override
    public Uni<SetResponse> set(final SetRequest request) {
        // Fatia atual cobre apenas GET; SET entra no próximo passo. Sinalizamos
        // ao cliente com o status padrão para operação não implementada.
        return Uni.createFrom().failure(
                Status.UNIMPLEMENTED.withDescription("SET ainda não implementado").asRuntimeException());
    }

    private static GetResponse toGetResponse(final Response response) {
        final GetResponse.Builder builder = GetResponse.newBuilder();
        // No cliente Vert.x, uma resposta bulk nil do Redis chega como Response
        // null. Distinguimos "chave inexistente" (nil → value ausente) de "string
        // vazia" (value presente, porém vazio) deixando o campo optional não-setado
        // quando null. toBytes() preserva os bytes crus (binary-safe).
        if (response != null) {
            builder.setValue(ByteString.copyFrom(response.toBytes()));
        }
        return builder.build();
    }
}
