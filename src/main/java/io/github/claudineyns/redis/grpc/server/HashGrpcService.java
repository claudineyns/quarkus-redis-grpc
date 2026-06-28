package io.github.claudineyns.redis.grpc.server;

import java.util.List;

import org.jboss.logging.Logger;
import org.jboss.logging.MDC;

import com.google.protobuf.ByteString;

import io.github.claudineyns.redis.grpc.v1.FieldValue;
import io.github.claudineyns.redis.grpc.v1.HDelRequest;
import io.github.claudineyns.redis.grpc.v1.HExistsRequest;
import io.github.claudineyns.redis.grpc.v1.HGetAllRequest;
import io.github.claudineyns.redis.grpc.v1.HGetRequest;
import io.github.claudineyns.redis.grpc.v1.HKeysRequest;
import io.github.claudineyns.redis.grpc.v1.HLenRequest;
import io.github.claudineyns.redis.grpc.v1.HMGetRequest;
import io.github.claudineyns.redis.grpc.v1.HMGetResponse;
import io.github.claudineyns.redis.grpc.v1.HScanRequest;
import io.github.claudineyns.redis.grpc.v1.HScanResponse;
import io.github.claudineyns.redis.grpc.v1.HSetNxRequest;
import io.github.claudineyns.redis.grpc.v1.HSetRequest;
import io.github.claudineyns.redis.grpc.v1.HIncrByRequest;
import io.github.claudineyns.redis.grpc.v1.HValsRequest;
import io.github.claudineyns.redis.grpc.v1.HashChange;
import io.github.claudineyns.redis.grpc.v1.HashCount;
import io.github.claudineyns.redis.grpc.v1.HashCounter;
import io.github.claudineyns.redis.grpc.v1.HashEntries;
import io.github.claudineyns.redis.grpc.v1.HashExists;
import io.github.claudineyns.redis.grpc.v1.HashFields;
import io.github.claudineyns.redis.grpc.v1.HashService;
import io.github.claudineyns.redis.grpc.v1.HashValue;
import io.github.claudineyns.redis.grpc.v1.HashValues;
import io.grpc.Status;
import io.quarkus.grpc.GrpcService;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.redis.client.Command;
import io.vertx.mutiny.redis.client.Redis;
import io.vertx.mutiny.redis.client.Request;
import io.vertx.mutiny.redis.client.Response;
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
    private static final String CMD_HMGET = "HMGET";
    private static final String CMD_HGETALL = "HGETALL";
    private static final String CMD_HKEYS = "HKEYS";
    private static final String CMD_HVALS = "HVALS";
    private static final String CMD_HSETNX = "HSETNX";
    private static final String CMD_HINCRBY = "HINCRBY";
    private static final String CMD_HSCAN = "HSCAN";

    // Opções do HSCAN.
    private static final String OPT_MATCH = "MATCH";
    private static final String OPT_COUNT = "COUNT";

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

    @Override
    public Uni<HMGetResponse> hMGet(final HMGetRequest request) {
        MDC.put(LogFields.COMMAND, CMD_HMGET);
        MDC.put(LogFields.KEY, request.getKey());
        final List<String> fields = request.getFieldsList();
        LOG.debugf("HMGET recebido (fields=%d)", fields.size());

        if (fields.isEmpty()) {
            return Uni.createFrom().failure(Status.INVALID_ARGUMENT
                    .withDescription("HMGET exige ao menos um campo").asRuntimeException());
        }

        final Request command = Request.cmd(Command.HMGET).arg(request.getKey());
        for (final String field : fields) {
            command.arg(field);
        }
        final long startNanos = System.nanoTime();

        return redis.send(command)
                .map(response -> {
                    putRedisDuration(startNanos);
                    // Array alinhado aos campos do request; elemento nulo = nil
                    // (campo ausente). HMGET não devolve WRONGTYPE por campo.
                    final HMGetResponse.Builder result = HMGetResponse.newBuilder();
                    if (response != null) {
                        for (int i = 0; i < response.size(); i++) {
                            final Response item = response.get(i);
                            final HashValue.Builder value = HashValue.newBuilder();
                            if (item != null) {
                                value.setValue(ByteString.copyFrom(item.toBytes()));
                            }
                            result.addValues(value.build());
                        }
                    }
                    LOG.debugf("HMGET concluído (values=%d)", result.getValuesCount());
                    return result.build();
                })
                .onFailure().transform(RedisErrors::toStatus);
    }

    @Override
    public Uni<HashEntries> hGetAll(final HGetAllRequest request) {
        MDC.put(LogFields.COMMAND, CMD_HGETALL);
        MDC.put(LogFields.KEY, request.getKey());
        LOG.debug("HGETALL recebido");

        final Request command = Request.cmd(Command.HGETALL).arg(request.getKey());
        final long startNanos = System.nanoTime();

        return redis.send(command)
                .map(response -> {
                    putRedisDuration(startNanos);
                    final HashEntries.Builder result = HashEntries.newBuilder();
                    appendEntries(result, response);
                    LOG.debugf("HGETALL concluído (entries=%d)", result.getEntriesCount());
                    return result.build();
                })
                .onFailure().transform(RedisErrors::toStatus);
    }

    @Override
    public Uni<HashFields> hKeys(final HKeysRequest request) {
        MDC.put(LogFields.COMMAND, CMD_HKEYS);
        MDC.put(LogFields.KEY, request.getKey());
        LOG.debug("HKEYS recebido");

        final Request command = Request.cmd(Command.HKEYS).arg(request.getKey());
        final long startNanos = System.nanoTime();

        return redis.send(command)
                .map(response -> {
                    putRedisDuration(startNanos);
                    final HashFields.Builder result = HashFields.newBuilder();
                    if (response != null) {
                        for (final Response field : response) {
                            result.addFields(field.toString());
                        }
                    }
                    LOG.debugf("HKEYS concluído (fields=%d)", result.getFieldsCount());
                    return result.build();
                })
                .onFailure().transform(RedisErrors::toStatus);
    }

    @Override
    public Uni<HashValues> hVals(final HValsRequest request) {
        MDC.put(LogFields.COMMAND, CMD_HVALS);
        MDC.put(LogFields.KEY, request.getKey());
        LOG.debug("HVALS recebido");

        final Request command = Request.cmd(Command.HVALS).arg(request.getKey());
        final long startNanos = System.nanoTime();

        return redis.send(command)
                .map(response -> {
                    putRedisDuration(startNanos);
                    final HashValues.Builder result = HashValues.newBuilder();
                    if (response != null) {
                        for (final Response value : response) {
                            result.addValues(ByteString.copyFrom(value.toBytes()));
                        }
                    }
                    LOG.debugf("HVALS concluído (values=%d)", result.getValuesCount());
                    return result.build();
                })
                .onFailure().transform(RedisErrors::toStatus);
    }

    @Override
    public Uni<HashChange> hSetNx(final HSetNxRequest request) {
        MDC.put(LogFields.COMMAND, CMD_HSETNX);
        MDC.put(LogFields.KEY, request.getKey());
        LOG.debug("HSETNX recebido");

        final Request command = Request.cmd(Command.HSETNX)
                .arg(request.getKey()).arg(request.getField()).arg(request.getValue().toByteArray());
        final long startNanos = System.nanoTime();

        return redis.send(command)
                .map(response -> {
                    putRedisDuration(startNanos);
                    // HSETNX: 1 = criou (campo não existia); 0 = já existia (não grava).
                    final boolean applied = response != null && response.toLong() == 1L;
                    LOG.debugf("HSETNX concluído (applied=%s)", applied);
                    return HashChange.newBuilder().setApplied(applied).build();
                })
                .onFailure().transform(RedisErrors::toStatus);
    }

    @Override
    public Uni<HashCounter> hIncrBy(final HIncrByRequest request) {
        MDC.put(LogFields.COMMAND, CMD_HINCRBY);
        MDC.put(LogFields.KEY, request.getKey());
        LOG.debug("HINCRBY recebido");

        final Request command = Request.cmd(Command.HINCRBY)
                .arg(request.getKey()).arg(request.getField()).arg(request.getIncrement());
        final long startNanos = System.nanoTime();

        return redis.send(command)
                .map(response -> {
                    putRedisDuration(startNanos);
                    // HINCRBY devolve o novo valor do campo (campo ausente conta como 0).
                    final HashCounter result = HashCounter.newBuilder().setValue(response.toLong()).build();
                    LOG.debugf("HINCRBY concluído (value=%d)", result.getValue());
                    return result;
                })
                .onFailure().transform(RedisErrors::toStatus);
    }

    @Override
    public Uni<HScanResponse> hScan(final HScanRequest request) {
        MDC.put(LogFields.COMMAND, CMD_HSCAN);
        MDC.put(LogFields.KEY, request.getKey());
        LOG.debug("HSCAN recebido");

        // HSCAN key cursor [MATCH pattern] [COUNT count]. Opções só anexadas quando
        // informadas (proto3 optional → hasX()).
        final Request command = Request.cmd(Command.HSCAN).arg(request.getKey()).arg(request.getCursor());
        if (request.hasMatch()) {
            command.arg(OPT_MATCH).arg(request.getMatch());
        }
        if (request.hasCount()) {
            command.arg(OPT_COUNT).arg(request.getCount());
        }
        final long startNanos = System.nanoTime();

        return redis.send(command)
                .map(response -> {
                    putRedisDuration(startNanos);
                    // HSCAN devolve [cursor, [f1,v1,f2,v2,...]] — o 2º elemento é um
                    // array PLANO de pares (não é mapa), iterado por índice.
                    final String cursor = response.get(0).toString();
                    final HScanResponse.Builder result = HScanResponse.newBuilder().setCursor(cursor);
                    final Response pairs = response.get(1);
                    if (pairs != null) {
                        for (int i = 0; i + 1 < pairs.size(); i += 2) {
                            result.addEntries(FieldValue.newBuilder()
                                    .setField(pairs.get(i).toString())
                                    .setValue(ByteString.copyFrom(pairs.get(i + 1).toBytes())).build());
                        }
                    }
                    LOG.debugf("HSCAN concluído (cursor=%s, entries=%d)", cursor, result.getEntriesCount());
                    return result.build();
                })
                .onFailure().transform(RedisErrors::toStatus);
    }

    /**
     * HGETALL: preenche os pares campo/valor. O Redis devolve um array plano
     * [f1,v1,...] em RESP2 e um MAPA em RESP3 (cujo {@code type()} ainda é MULTI,
     * mas {@code get(int)} lança "Multi is a Map"). Usamos {@code getKeys()}
     * (não-nulo = mapa) para distinguir e tratamos os dois casos.
     */
    private static void appendEntries(final HashEntries.Builder result, final Response response) {
        if (response == null) {
            return;
        }
        final java.util.Set<String> keys = response.getKeys();
        if (keys != null) {
            for (final String field : keys) {
                result.addEntries(FieldValue.newBuilder()
                        .setField(field)
                        .setValue(ByteString.copyFrom(response.get(field).toBytes())).build());
            }
            return;
        }
        for (int i = 0; i + 1 < response.size(); i += 2) {
            result.addEntries(FieldValue.newBuilder()
                    .setField(response.get(i).toString())
                    .setValue(ByteString.copyFrom(response.get(i + 1).toBytes())).build());
        }
    }

    private static void putRedisDuration(final long startNanos) {
        MDC.put(LogFields.REDIS_DURATION_MS,
                Long.toString((System.nanoTime() - startNanos) / 1_000_000L));
    }
}
