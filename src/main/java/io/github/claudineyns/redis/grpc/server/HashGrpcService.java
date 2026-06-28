package io.github.claudineyns.redis.grpc.server;

import java.util.List;

import org.jboss.logging.Logger;
import org.jboss.logging.MDC;

import com.google.protobuf.ByteString;

import io.github.claudineyns.redis.grpc.v1.FieldValue;
import io.github.claudineyns.redis.grpc.v1.HDelRequest;
import io.github.claudineyns.redis.grpc.v1.HExistsRequest;
import io.github.claudineyns.redis.grpc.v1.HGetRequest;
import io.github.claudineyns.redis.grpc.v1.HLenRequest;
import io.github.claudineyns.redis.grpc.v1.HSetRequest;
import io.github.claudineyns.redis.grpc.v1.HashCount;
import io.github.claudineyns.redis.grpc.v1.HashExists;
import io.github.claudineyns.redis.grpc.v1.HashService;
import io.github.claudineyns.redis.grpc.v1.HashValue;
import io.grpc.Status;
import io.quarkus.grpc.GrpcService;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.redis.client.Command;
import io.vertx.mutiny.redis.client.Redis;
import io.vertx.mutiny.redis.client.Request;
import jakarta.inject.Inject;

/**
 * Família HASH — implementação gRPC do {@code HashService}.
 *
 * <p>Tradução 1:1 de comandos Redis (DESIGN seção 2). Fatia 1: HSET, HGET, HDEL,
 * HEXISTS, HLEN. Campos são {@code string}; valores são {@code bytes}
 * (binário-seguros, como no StringService).
 */
@GrpcService
public class HashGrpcService implements HashService {

    private static final Logger LOG = Logger.getLogger(HashGrpcService.class);

    private static final String CMD_HSET = "HSET";
    private static final String CMD_HGET = "HGET";
    private static final String CMD_HDEL = "HDEL";
    private static final String CMD_HEXISTS = "HEXISTS";
    private static final String CMD_HLEN = "HLEN";

    @Inject
    Redis redis; // CDI: não pode ser final (exceção da convenção de DESIGN seção 10)

    @Override
    public Uni<HashCount> hSet(final HSetRequest request) {
        MDC.put(LogFields.COMMAND, CMD_HSET);
        MDC.put(LogFields.KEY, request.getKey());
        final List<FieldValue> fields = request.getFieldsList();
        LOG.debugf("HSET recebido (fields=%d)", fields.size());

        if (fields.isEmpty()) {
            return Uni.createFrom().failure(Status.INVALID_ARGUMENT
                    .withDescription("HSET exige ao menos um par campo/valor").asRuntimeException());
        }

        final Request command = Request.cmd(Command.HSET).arg(request.getKey());
        for (final FieldValue field : fields) {
            command.arg(field.getField()).arg(field.getValue().toByteArray());
        }
        final long startNanos = System.nanoTime();

        return redis.send(command)
                .map(response -> {
                    putRedisDuration(startNanos);
                    // HSET devolve o nº de campos NOVOS (atualizações não contam).
                    final HashCount result = HashCount.newBuilder().setCount(response.toLong()).build();
                    LOG.debugf("HSET concluído (count=%d)", result.getCount());
                    return result;
                })
                .onFailure().transform(RedisErrors::toStatus);
    }

    @Override
    public Uni<HashValue> hGet(final HGetRequest request) {
        MDC.put(LogFields.COMMAND, CMD_HGET);
        MDC.put(LogFields.KEY, request.getKey());
        LOG.debug("HGET recebido");

        final Request command = Request.cmd(Command.HGET).arg(request.getKey()).arg(request.getField());
        final long startNanos = System.nanoTime();

        return redis.send(command)
                .map(response -> {
                    putRedisDuration(startNanos);
                    // optional: nil (campo/chave ausente) → sem value; senão bytes crus.
                    final HashValue.Builder result = HashValue.newBuilder();
                    if (response != null) {
                        result.setValue(ByteString.copyFrom(response.toBytes()));
                    }
                    LOG.debugf("HGET concluído (found=%s)", response != null);
                    return result.build();
                })
                .onFailure().transform(RedisErrors::toStatus);
    }

    @Override
    public Uni<HashCount> hDel(final HDelRequest request) {
        MDC.put(LogFields.COMMAND, CMD_HDEL);
        MDC.put(LogFields.KEY, request.getKey());
        final List<String> fields = request.getFieldsList();
        LOG.debugf("HDEL recebido (fields=%d)", fields.size());

        if (fields.isEmpty()) {
            return Uni.createFrom().failure(Status.INVALID_ARGUMENT
                    .withDescription("HDEL exige ao menos um campo").asRuntimeException());
        }

        final Request command = Request.cmd(Command.HDEL).arg(request.getKey());
        for (final String field : fields) {
            command.arg(field);
        }
        final long startNanos = System.nanoTime();

        return redis.send(command)
                .map(response -> {
                    putRedisDuration(startNanos);
                    final HashCount result = HashCount.newBuilder().setCount(response.toLong()).build();
                    LOG.debugf("HDEL concluído (count=%d)", result.getCount());
                    return result;
                })
                .onFailure().transform(RedisErrors::toStatus);
    }

    @Override
    public Uni<HashExists> hExists(final HExistsRequest request) {
        MDC.put(LogFields.COMMAND, CMD_HEXISTS);
        MDC.put(LogFields.KEY, request.getKey());
        LOG.debug("HEXISTS recebido");

        final Request command = Request.cmd(Command.HEXISTS).arg(request.getKey()).arg(request.getField());
        final long startNanos = System.nanoTime();

        return redis.send(command)
                .map(response -> {
                    putRedisDuration(startNanos);
                    final boolean exists = response != null && response.toLong() == 1L;
                    LOG.debugf("HEXISTS concluído (exists=%s)", exists);
                    return HashExists.newBuilder().setExists(exists).build();
                })
                .onFailure().transform(RedisErrors::toStatus);
    }

    @Override
    public Uni<HashCount> hLen(final HLenRequest request) {
        MDC.put(LogFields.COMMAND, CMD_HLEN);
        MDC.put(LogFields.KEY, request.getKey());
        LOG.debug("HLEN recebido");

        final Request command = Request.cmd(Command.HLEN).arg(request.getKey());
        final long startNanos = System.nanoTime();

        return redis.send(command)
                .map(response -> {
                    putRedisDuration(startNanos);
                    final HashCount result = HashCount.newBuilder().setCount(response.toLong()).build();
                    LOG.debugf("HLEN concluído (count=%d)", result.getCount());
                    return result;
                })
                .onFailure().transform(RedisErrors::toStatus);
    }

    private static void putRedisDuration(final long startNanos) {
        MDC.put(LogFields.REDIS_DURATION_MS,
                Long.toString((System.nanoTime() - startNanos) / 1_000_000L));
    }
}
