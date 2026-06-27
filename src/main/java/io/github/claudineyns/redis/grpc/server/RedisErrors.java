package io.github.claudineyns.redis.grpc.server;

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

    private RedisErrors() {
    }

    public static StatusRuntimeException toStatus(final Throwable failure) {
        final String message = failure.getMessage() == null ? "" : failure.getMessage();
        final String prefix = redisErrorPrefix(message);

        final Status status = switch (prefix) {
            case "WRONGTYPE" -> Status.FAILED_PRECONDITION;
            case "OOM" -> Status.RESOURCE_EXHAUSTED;
            case "NOAUTH", "WRONGPASS" -> Status.INTERNAL;
            case "" -> Status.UNAVAILABLE; // sem prefixo RESP → falha de infra/transporte
            default -> Status.INTERNAL;    // demais erros RESP (ex.: "ERR ...")
        };

        return status.withDescription(message).withCause(failure).asRuntimeException();
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
        return looksLikeRespCode ? firstToken : "";
    }
}
