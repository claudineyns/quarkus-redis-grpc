package io.github.claudineyns.redis.grpc.server;

/**
 * Nomes das chaves de MDC usadas nos logs (DESIGN seção 8.1).
 *
 * <p>Centralizadas como constantes para evitar literais repetidos entre o
 * interceptor e os serviços (e prevenir o Sonar {@code java:S1192}).
 */
public final class LogFields {

    public static final String REQUEST_ID = "requestId";
    public static final String RPC = "rpc";
    public static final String COMMAND = "command";
    public static final String KEY = "key";
    public static final String STATUS = "status";
    public static final String DURATION_MS = "durationMs";
    public static final String REDIS_DURATION_MS = "redisDurationMs";

    private LogFields() {
    }
}
