package io.github.claudineyns.redis.grpc.server;

import org.jboss.logging.Logger;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;

/**
 * Mapeia falhas do cliente Redis para status gRPC.
 *
 * <p>Regra (DESIGN seção 5.1): erro RESP real do Redis vira status gRPC, com a
 * mensagem crua preservada na descrição; falha de transporte/infra vira
 * {@code UNAVAILABLE}.
 */
public final class RedisErrors {

    private static final Logger LOG = Logger.getLogger(RedisErrors.class);

    // Constantes de tempo de compilação (literais) — válidas como rótulos de
    // 'case' e evitam literais repetidos (Sonar java:S1192).
    private static final String EMPTY = "";
    private static final String ERR_WRONGTYPE = "WRONGTYPE";
    private static final String ERR_OOM = "OOM";
    private static final String ERR_NOAUTH = "NOAUTH";
    private static final String ERR_WRONGPASS = "WRONGPASS";

    // Trechos de mensagem para mapeamento semântico (ex.: contadores).
    private static final String MSG_NOT_INTEGER = "not an integer";
    private static final String MSG_OVERFLOW = "overflow";

    private RedisErrors() {
    }

    public static StatusRuntimeException toStatus(final Throwable failure) {
        final String message = failure.getMessage() == null ? EMPTY : failure.getMessage();
        final Status status = mapStatus(message);

        LOG.debugf("erro do Redis mapeado -> status %s", status.getCode().name());
        return status.withDescription(message).withCause(failure).asRuntimeException();
    }

    /**
     * Mapeia a mensagem de falha para um status gRPC: primeiro casos semânticos
     * por conteúdo (ex.: contadores), depois o código RESP (prefixo).
     */
    private static Status mapStatus(final String message) {
        if (message.contains(MSG_NOT_INTEGER)) {
            return Status.FAILED_PRECONDITION; // valor armazenado não é inteiro
        }
        if (message.contains(MSG_OVERFLOW)) {
            return Status.OUT_OF_RANGE;        // estouro de INCR/DECR
        }
        return switch (redisErrorPrefix(message)) {
            case ERR_WRONGTYPE -> Status.FAILED_PRECONDITION;
            case ERR_OOM -> Status.RESOURCE_EXHAUSTED;
            case ERR_NOAUTH, ERR_WRONGPASS -> Status.INTERNAL;
            case EMPTY -> Status.UNAVAILABLE;  // sem prefixo RESP → infra/transporte
            default -> Status.INTERNAL;        // demais erros RESP (ex.: "ERR ...")
        };
    }

    /**
     * Erros RESP começam com um código em letras maiúsculas (ex.: "WRONGTYPE ...",
     * "ERR ..."). Devolve esse código, ou "" se a mensagem não parecer um erro RESP.
     */
    private static String redisErrorPrefix(final String message) {
        final int space = message.indexOf(' ');
        final String firstToken = space < 0 ? message : message.substring(0, space);
        final boolean looksLikeRespCode = !firstToken.isEmpty()
                && firstToken.chars().allMatch(c -> c >= 'A' && c <= 'Z');
        return looksLikeRespCode ? firstToken : EMPTY;
    }
}
