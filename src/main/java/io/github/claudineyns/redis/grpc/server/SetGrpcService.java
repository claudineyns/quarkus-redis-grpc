package io.github.claudineyns.redis.grpc.server;

import java.util.List;

import org.jboss.logging.Logger;
import org.jboss.logging.MDC;

import io.github.claudineyns.redis.grpc.v1.SAddRequest;
import io.github.claudineyns.redis.grpc.v1.SCardRequest;
import io.github.claudineyns.redis.grpc.v1.SIsMemberRequest;
import io.github.claudineyns.redis.grpc.v1.SMIsMemberRequest;
import io.github.claudineyns.redis.grpc.v1.SMembersRequest;
import io.github.claudineyns.redis.grpc.v1.SPopRequest;
import io.github.claudineyns.redis.grpc.v1.SRemRequest;
import io.github.claudineyns.redis.grpc.v1.SScanRequest;
import io.github.claudineyns.redis.grpc.v1.SScanResponse;
import io.github.claudineyns.redis.grpc.v1.SetCount;
import io.github.claudineyns.redis.grpc.v1.SetMembers;
import io.github.claudineyns.redis.grpc.v1.SetMembership;
import io.github.claudineyns.redis.grpc.v1.SetMemberships;
import io.github.claudineyns.redis.grpc.v1.SetService;
import io.grpc.Status;
import io.quarkus.grpc.GrpcService;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.redis.client.Command;
import io.vertx.mutiny.redis.client.Redis;
import io.vertx.mutiny.redis.client.Request;
import io.vertx.mutiny.redis.client.Response;
import jakarta.inject.Inject;

/**
 * Família SET — implementação gRPC do {@code SetService}.
 *
 * <p>Tradução 1:1 de comandos Redis (DESIGN seção 2). Fatia 1: SADD, SREM,
 * SCARD, SISMEMBER, SMISMEMBER, SMEMBERS. Membros são {@code string} (convenção
 * do projeto).
 */
@GrpcService
public class SetGrpcService implements SetService {

    private static final Logger LOG = Logger.getLogger(SetGrpcService.class);

    private static final String CMD_SADD = "SADD";
    private static final String CMD_SREM = "SREM";
    private static final String CMD_SCARD = "SCARD";
    private static final String CMD_SISMEMBER = "SISMEMBER";
    private static final String CMD_SMISMEMBER = "SMISMEMBER";
    private static final String CMD_SMEMBERS = "SMEMBERS";
    private static final String CMD_SPOP = "SPOP";
    private static final String CMD_SSCAN = "SSCAN";

    // Opções do SSCAN.
    private static final String OPT_MATCH = "MATCH";
    private static final String OPT_COUNT = "COUNT";

    @Inject
    Redis redis; // CDI: não pode ser final (exceção da convenção de DESIGN seção 10)

    @Override
    public Uni<SetCount> sAdd(final SAddRequest request) {
        return countOverMembers(CMD_SADD, Command.SADD, request.getKey(), request.getMembersList());
    }

    @Override
    public Uni<SetCount> sRem(final SRemRequest request) {
        return countOverMembers(CMD_SREM, Command.SREM, request.getKey(), request.getMembersList());
    }

    @Override
    public Uni<SetCount> sCard(final SCardRequest request) {
        MDC.put(LogFields.COMMAND, CMD_SCARD);
        MDC.put(LogFields.KEY, request.getKey());
        LOG.debug("SCARD recebido");

        final Request command = Request.cmd(Command.SCARD).arg(request.getKey());
        final long startNanos = System.nanoTime();

        return redis.send(command)
                .map(response -> {
                    putRedisDuration(startNanos);
                    final SetCount result = SetCount.newBuilder().setCount(response.toLong()).build();
                    LOG.debugf("SCARD concluído (count=%d)", result.getCount());
                    return result;
                })
                .onFailure().transform(RedisErrors::toStatus);
    }

    @Override
    public Uni<SetMembership> sIsMember(final SIsMemberRequest request) {
        MDC.put(LogFields.COMMAND, CMD_SISMEMBER);
        MDC.put(LogFields.KEY, request.getKey());
        LOG.debug("SISMEMBER recebido");

        final Request command = Request.cmd(Command.SISMEMBER).arg(request.getKey()).arg(request.getMember());
        final long startNanos = System.nanoTime();

        return redis.send(command)
                .map(response -> {
                    putRedisDuration(startNanos);
                    final boolean member = response != null && response.toLong() == 1L;
                    LOG.debugf("SISMEMBER concluído (is_member=%s)", member);
                    return SetMembership.newBuilder().setIsMember(member).build();
                })
                .onFailure().transform(RedisErrors::toStatus);
    }

    @Override
    public Uni<SetMemberships> sMIsMember(final SMIsMemberRequest request) {
        MDC.put(LogFields.COMMAND, CMD_SMISMEMBER);
        MDC.put(LogFields.KEY, request.getKey());
        final List<String> members = request.getMembersList();
        LOG.debugf("SMISMEMBER recebido (members=%d)", members.size());

        if (members.isEmpty()) {
            return Uni.createFrom().failure(Status.INVALID_ARGUMENT
                    .withDescription("SMISMEMBER exige ao menos um membro").asRuntimeException());
        }

        final Request command = Request.cmd(Command.SMISMEMBER).arg(request.getKey());
        for (final String member : members) {
            command.arg(member);
        }
        final long startNanos = System.nanoTime();

        return redis.send(command)
                .map(response -> {
                    putRedisDuration(startNanos);
                    // SMISMEMBER devolve um array de 0/1, alinhado à ordem dos membros.
                    final SetMemberships.Builder result = SetMemberships.newBuilder();
                    for (final Response flag : response) {
                        result.addMembers(flag.toLong() == 1L);
                    }
                    LOG.debugf("SMISMEMBER concluído (members=%d)", result.getMembersCount());
                    return result.build();
                })
                .onFailure().transform(RedisErrors::toStatus);
    }

    @Override
    public Uni<SetMembers> sMembers(final SMembersRequest request) {
        MDC.put(LogFields.COMMAND, CMD_SMEMBERS);
        MDC.put(LogFields.KEY, request.getKey());
        LOG.debug("SMEMBERS recebido");

        final Request command = Request.cmd(Command.SMEMBERS).arg(request.getKey());
        final long startNanos = System.nanoTime();

        return redis.send(command)
                .map(response -> {
                    putRedisDuration(startNanos);
                    // SMEMBERS devolve um array (vazio se a chave não existe).
                    final SetMembers.Builder result = SetMembers.newBuilder();
                    if (response != null) {
                        for (final Response member : response) {
                            result.addMembers(member.toString());
                        }
                    }
                    LOG.debugf("SMEMBERS concluído (members=%d)", result.getMembersCount());
                    return result.build();
                })
                .onFailure().transform(RedisErrors::toStatus);
    }

    @Override
    public Uni<SetMembers> sPop(final SPopRequest request) {
        MDC.put(LogFields.COMMAND, CMD_SPOP);
        MDC.put(LogFields.KEY, request.getKey());
        final boolean hasCount = request.hasCount();
        LOG.debugf("SPOP recebido (count=%s)", hasCount ? request.getCount() : "-");

        final Request command = Request.cmd(Command.SPOP).arg(request.getKey());
        if (hasCount) {
            command.arg(request.getCount());
        }
        final long startNanos = System.nanoTime();

        return redis.send(command)
                .map(response -> {
                    putRedisDuration(startNanos);
                    // A forma da resposta difere conforme o count. Com count, vem
                    // um array com zero ou mais membros. Sem count, vem um único
                    // valor, ou nil quando a chave não existe.
                    final SetMembers.Builder result = SetMembers.newBuilder();
                    if (response != null) {
                        if (hasCount) {
                            for (final Response member : response) {
                                result.addMembers(member.toString());
                            }
                        } else {
                            result.addMembers(response.toString());
                        }
                    }
                    LOG.debugf("SPOP concluído (members=%d)", result.getMembersCount());
                    return result.build();
                })
                .onFailure().transform(RedisErrors::toStatus);
    }

    @Override
    public Uni<SScanResponse> sScan(final SScanRequest request) {
        MDC.put(LogFields.COMMAND, CMD_SSCAN);
        MDC.put(LogFields.KEY, request.getKey());
        LOG.debug("SSCAN recebido");

        // SSCAN key cursor [MATCH pattern] [COUNT count]. Opções só são anexadas
        // quando informadas (proto3 optional → hasX()). SSCAN não tem TYPE.
        final Request command = Request.cmd(Command.SSCAN).arg(request.getKey()).arg(request.getCursor());
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
                    // SSCAN devolve um array de 2: [0] = próximo cursor (bulk),
                    // [1] = array (possivelmente vazio) com os membros da página.
                    final String cursor = response.get(0).toString();
                    final SScanResponse.Builder result = SScanResponse.newBuilder().setCursor(cursor);
                    final Response members = response.get(1);
                    if (members != null) {
                        for (final Response member : members) {
                            result.addMembers(member.toString());
                        }
                    }
                    LOG.debugf("SSCAN concluído (cursor=%s, members=%d)", cursor, result.getMembersCount());
                    return result.build();
                })
                .onFailure().transform(RedisErrors::toStatus);
    }

    /**
     * SADD/SREM: comandos multi-membro que devolvem uma contagem (int64).
     * Lista vazia → INVALID_ARGUMENT (em vez do "ERR wrong number of args").
     */
    private Uni<SetCount> countOverMembers(final String label, final Command cmd,
            final String key, final List<String> members) {
        MDC.put(LogFields.COMMAND, label);
        MDC.put(LogFields.KEY, key);
        LOG.debugf("%s recebido (members=%d)", label, members.size());

        if (members.isEmpty()) {
            return Uni.createFrom().failure(Status.INVALID_ARGUMENT
                    .withDescription(label + " exige ao menos um membro").asRuntimeException());
        }

        final Request command = Request.cmd(cmd).arg(key);
        for (final String member : members) {
            command.arg(member);
        }
        final long startNanos = System.nanoTime();

        return redis.send(command)
                .map(response -> {
                    putRedisDuration(startNanos);
                    final SetCount result = SetCount.newBuilder().setCount(response.toLong()).build();
                    LOG.debugf("%s concluído (count=%d)", label, result.getCount());
                    return result;
                })
                .onFailure().transform(RedisErrors::toStatus);
    }

    private static void putRedisDuration(final long startNanos) {
        MDC.put(LogFields.REDIS_DURATION_MS,
                Long.toString((System.nanoTime() - startNanos) / 1_000_000L));
    }
}
