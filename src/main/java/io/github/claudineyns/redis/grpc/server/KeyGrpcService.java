package io.github.claudineyns.redis.grpc.server;

import java.util.List;

import org.jboss.logging.Logger;
import org.jboss.logging.MDC;

import io.github.claudineyns.redis.grpc.v1.DelRequest;
import io.github.claudineyns.redis.grpc.v1.ExistsRequest;
import io.github.claudineyns.redis.grpc.v1.ExpireAtRequest;
import io.github.claudineyns.redis.grpc.v1.ExpireCondition;
import io.github.claudineyns.redis.grpc.v1.ExpireRequest;
import io.github.claudineyns.redis.grpc.v1.KeyChange;
import io.github.claudineyns.redis.grpc.v1.KeyCount;
import io.github.claudineyns.redis.grpc.v1.KeyService;
import io.github.claudineyns.redis.grpc.v1.KeyType;
import io.github.claudineyns.redis.grpc.v1.PExpireAtRequest;
import io.github.claudineyns.redis.grpc.v1.PExpireRequest;
import io.github.claudineyns.redis.grpc.v1.PTtlRequest;
import io.github.claudineyns.redis.grpc.v1.PersistRequest;
import io.github.claudineyns.redis.grpc.v1.ScanRequest;
import io.github.claudineyns.redis.grpc.v1.ScanResponse;
import io.github.claudineyns.redis.grpc.v1.TtlRequest;
import io.github.claudineyns.redis.grpc.v1.TtlValue;
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
 * EXISTS, TYPE. Fatia 2: EXPIRE-família, PERSIST, TTL/PTTL. Fatia 3: SCAN.
 */
@GrpcService
public class KeyGrpcService implements KeyService {

    private static final Logger LOG = Logger.getLogger(KeyGrpcService.class);

    private static final String CMD_DEL = "DEL";
    private static final String CMD_UNLINK = "UNLINK";
    private static final String CMD_EXISTS = "EXISTS";
    private static final String CMD_TYPE = "TYPE";
    private static final String CMD_EXPIRE = "EXPIRE";
    private static final String CMD_PEXPIRE = "PEXPIRE";
    private static final String CMD_EXPIREAT = "EXPIREAT";
    private static final String CMD_PEXPIREAT = "PEXPIREAT";
    private static final String CMD_PERSIST = "PERSIST";
    private static final String CMD_TTL = "TTL";
    private static final String CMD_PTTL = "PTTL";
    private static final String CMD_SCAN = "SCAN";

    // Condições do EXPIRE (Redis 7+).
    private static final String OPT_NX = "NX";
    private static final String OPT_XX = "XX";
    private static final String OPT_GT = "GT";
    private static final String OPT_LT = "LT";

    // Opções do SCAN.
    private static final String OPT_MATCH = "MATCH";
    private static final String OPT_COUNT = "COUNT";
    private static final String OPT_TYPE = "TYPE";

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

    @Override
    public Uni<KeyChange> expire(final ExpireRequest request) {
        MDC.put(LogFields.COMMAND, CMD_EXPIRE);
        MDC.put(LogFields.KEY, request.getKey());
        LOG.debug("EXPIRE recebido");
        final Request command = Request.cmd(Command.EXPIRE)
                .arg(request.getKey()).arg(request.getSeconds());
        appendExpireCondition(command, request.getCondition());
        return sendChange(command, CMD_EXPIRE);
    }

    @Override
    public Uni<KeyChange> pExpire(final PExpireRequest request) {
        MDC.put(LogFields.COMMAND, CMD_PEXPIRE);
        MDC.put(LogFields.KEY, request.getKey());
        LOG.debug("PEXPIRE recebido");
        final Request command = Request.cmd(Command.PEXPIRE)
                .arg(request.getKey()).arg(request.getMillis());
        appendExpireCondition(command, request.getCondition());
        return sendChange(command, CMD_PEXPIRE);
    }

    @Override
    public Uni<KeyChange> expireAt(final ExpireAtRequest request) {
        MDC.put(LogFields.COMMAND, CMD_EXPIREAT);
        MDC.put(LogFields.KEY, request.getKey());
        LOG.debug("EXPIREAT recebido");
        final Request command = Request.cmd(Command.EXPIREAT)
                .arg(request.getKey()).arg(request.getUnixSeconds());
        appendExpireCondition(command, request.getCondition());
        return sendChange(command, CMD_EXPIREAT);
    }

    @Override
    public Uni<KeyChange> pExpireAt(final PExpireAtRequest request) {
        MDC.put(LogFields.COMMAND, CMD_PEXPIREAT);
        MDC.put(LogFields.KEY, request.getKey());
        LOG.debug("PEXPIREAT recebido");
        final Request command = Request.cmd(Command.PEXPIREAT)
                .arg(request.getKey()).arg(request.getUnixMillis());
        appendExpireCondition(command, request.getCondition());
        return sendChange(command, CMD_PEXPIREAT);
    }

    @Override
    public Uni<KeyChange> persist(final PersistRequest request) {
        MDC.put(LogFields.COMMAND, CMD_PERSIST);
        MDC.put(LogFields.KEY, request.getKey());
        LOG.debug("PERSIST recebido");
        return sendChange(Request.cmd(Command.PERSIST).arg(request.getKey()), CMD_PERSIST);
    }

    @Override
    public Uni<TtlValue> ttl(final TtlRequest request) {
        MDC.put(LogFields.COMMAND, CMD_TTL);
        MDC.put(LogFields.KEY, request.getKey());
        LOG.debug("TTL recebido");
        return sendTtl(Request.cmd(Command.TTL).arg(request.getKey()), CMD_TTL);
    }

    @Override
    public Uni<TtlValue> pTtl(final PTtlRequest request) {
        MDC.put(LogFields.COMMAND, CMD_PTTL);
        MDC.put(LogFields.KEY, request.getKey());
        LOG.debug("PTTL recebido");
        return sendTtl(Request.cmd(Command.PTTL).arg(request.getKey()), CMD_PTTL);
    }

    @Override
    public Uni<ScanResponse> scan(final ScanRequest request) {
        MDC.put(LogFields.COMMAND, CMD_SCAN);
        LOG.debug("SCAN recebido");

        // SCAN cursor [MATCH pattern] [COUNT count] [TYPE type]. Opções só são
        // anexadas quando informadas (proto3 optional → hasX()).
        final Request command = Request.cmd(Command.SCAN).arg(request.getCursor());
        if (request.hasMatch()) {
            command.arg(OPT_MATCH).arg(request.getMatch());
        }
        if (request.hasCount()) {
            command.arg(OPT_COUNT).arg(request.getCount());
        }
        if (request.hasType()) {
            command.arg(OPT_TYPE).arg(request.getType());
        }
        final long startNanos = System.nanoTime();

        return redis.send(command)
                .map(response -> {
                    MDC.put(LogFields.REDIS_DURATION_MS,
                            Long.toString((System.nanoTime() - startNanos) / 1_000_000L));
                    // SCAN devolve um array de 2: [0] = próximo cursor (bulk),
                    // [1] = array (possivelmente vazio) com as chaves da página.
                    final String cursor = response.get(0).toString();
                    final ScanResponse.Builder result = ScanResponse.newBuilder().setCursor(cursor);
                    final Response keys = response.get(1);
                    if (keys != null) {
                        for (final Response key : keys) {
                            result.addKeys(key.toString());
                        }
                    }
                    LOG.debugf("SCAN concluído (cursor=%s, keys=%d)", cursor, result.getKeysCount());
                    return result.build();
                })
                .onFailure().transform(RedisErrors::toStatus);
    }

    /** Acrescenta a condição NX/XX/GT/LT ao comando, quando informada. */
    private static void appendExpireCondition(final Request command, final ExpireCondition condition) {
        switch (condition) {
            case EXPIRE_CONDITION_NX -> command.arg(OPT_NX);
            case EXPIRE_CONDITION_XX -> command.arg(OPT_XX);
            case EXPIRE_CONDITION_GT -> command.arg(OPT_GT);
            case EXPIRE_CONDITION_LT -> command.arg(OPT_LT);
            default -> { /* UNSPECIFIED / UNRECOGNIZED → sem condição */ }
        }
    }

    /** EXPIRE-família + PERSIST: traduz o 0/1 do Redis em KeyChange{applied}. */
    private Uni<KeyChange> sendChange(final Request command, final String label) {
        final long startNanos = System.nanoTime();
        return redis.send(command)
                .map(response -> {
                    MDC.put(LogFields.REDIS_DURATION_MS,
                            Long.toString((System.nanoTime() - startNanos) / 1_000_000L));
                    final boolean applied = response != null && response.toLong() == 1L;
                    LOG.debugf("%s concluído (applied=%s)", label, applied);
                    return KeyChange.newBuilder().setApplied(applied).build();
                })
                .onFailure().transform(RedisErrors::toStatus);
    }

    /** TTL/PTTL: tempo restante; -1 sem TTL, -2 chave inexistente (crus). */
    private Uni<TtlValue> sendTtl(final Request command, final String label) {
        final long startNanos = System.nanoTime();
        return redis.send(command)
                .map(response -> {
                    MDC.put(LogFields.REDIS_DURATION_MS,
                            Long.toString((System.nanoTime() - startNanos) / 1_000_000L));
                    final long value = response.toLong();
                    LOG.debugf("%s concluído (value=%d)", label, value);
                    return TtlValue.newBuilder().setValue(value).build();
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
