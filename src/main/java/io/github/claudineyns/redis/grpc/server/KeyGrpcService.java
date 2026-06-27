package io.github.claudineyns.redis.grpc.server;

import java.util.List;

import org.jboss.logging.Logger;
import org.jboss.logging.MDC;

import io.github.claudineyns.redis.grpc.v1.DelRequest;
import io.github.claudineyns.redis.grpc.v1.ExistsRequest;
import io.github.claudineyns.redis.grpc.v1.KeyCount;
import io.github.claudineyns.redis.grpc.v1.KeyService;
import io.github.claudineyns.redis.grpc.v1.KeyType;
import io.github.claudineyns.redis.grpc.v1.TypeRequest;
import io.github.claudineyns.redis.grpc.v1.UnlinkRequest;
import io.grpc.Status;
import io.quarkus.grpc.GrpcService;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.redis.client.Command;
import io.vertx.mutiny.redis.client.Redis;
import io.vertx.mutiny.redis.client.Request;
import io.vertx.mutiny.redis.client.Response;
import jakarta.inject.Inject;

/**
 * Família KEY (cross-type) — implementação gRPC do {@code KeyService}.
 *
 * <p>Tradução 1:1 de comandos Redis (DESIGN seção 2). Fatia 1: DEL, UNLINK,
 * EXISTS, TYPE.
 */
@GrpcService
public class KeyGrpcService implements KeyService {

    private static final Logger LOG = Logger.getLogger(KeyGrpcService.class);

    private static final String CMD_DEL = "DEL";
    private static final String CMD_UNLINK = "UNLINK";
    private static final String CMD_EXISTS = "EXISTS";
    private static final String CMD_TYPE = "TYPE";

    private static final String TYPE_NONE = "none";

    @Inject
    Redis redis; // CDI: não pode ser final (exceção da convenção de DESIGN seção 10)

    @Override
    public Uni<KeyCount> del(final DelRequest request) {
        return countOverKeys(CMD_DEL, Command.DEL, request.getKeysList());
    }

    @Override
    public Uni<KeyCount> unlink(final UnlinkRequest request) {
        return countOverKeys(CMD_UNLINK, Command.UNLINK, request.getKeysList());
    }

    @Override
    public Uni<KeyCount> exists(final ExistsRequest request) {
        return countOverKeys(CMD_EXISTS, Command.EXISTS, request.getKeysList());
    }

    @Override
    public Uni<KeyType> type(final TypeRequest request) {
        MDC.put(LogFields.COMMAND, CMD_TYPE);
        MDC.put(LogFields.KEY, request.getKey());
        LOG.debug("TYPE recebido");

        final Request command = Request.cmd(Command.TYPE).arg(request.getKey());
        final long startNanos = System.nanoTime();

        return redis.send(command)
                .map(response -> {
                    MDC.put(LogFields.REDIS_DURATION_MS,
                            Long.toString((System.nanoTime() - startNanos) / 1_000_000L));
                    // TYPE devolve uma simple string ("string", "hash", ... ou
                    // "none" quando a chave não existe).
                    final String type = response == null ? TYPE_NONE : response.toString();
                    LOG.debugf("TYPE concluído (type=%s)", type);
                    return KeyType.newBuilder().setType(type).build();
                })
                .onFailure().transform(RedisErrors::toStatus);
    }

    /**
     * DEL/UNLINK/EXISTS: comandos multi-chave que devolvem uma contagem (int64).
     * Lista vazia → INVALID_ARGUMENT (em vez do "ERR wrong number of args").
     */
    private Uni<KeyCount> countOverKeys(final String label, final Command cmd,
            final List<String> keys) {
        MDC.put(LogFields.COMMAND, label);
        LOG.debugf("%s recebido (keys=%d)", label, keys.size());

        if (keys.isEmpty()) {
            return Uni.createFrom().failure(Status.INVALID_ARGUMENT
                    .withDescription(label + " exige ao menos uma chave").asRuntimeException());
        }

        final Request command = Request.cmd(cmd);
        for (final String key : keys) {
            command.arg(key);
        }
        final long startNanos = System.nanoTime();

        return redis.send(command)
                .map(response -> {
                    MDC.put(LogFields.REDIS_DURATION_MS,
                            Long.toString((System.nanoTime() - startNanos) / 1_000_000L));
                    final KeyCount result = KeyCount.newBuilder()
                            .setCount(response.toLong()).build();
                    LOG.debugf("%s concluído (count=%d)", label, result.getCount());
                    return result;
                })
                .onFailure().transform(RedisErrors::toStatus);
    }
}
