package io.github.claudineyns.redis.grpc.server;

import java.util.UUID;

import org.jboss.logging.Logger;
import org.jboss.logging.MDC;

import io.grpc.ForwardingServerCall;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.quarkus.grpc.GlobalInterceptor;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Interceptor de logging/observabilidade aplicado a todas as chamadas gRPC
 * (DESIGN seção 8.1).
 *
 * <p>No início da chamada popula o MDC com {@code requestId} e {@code rpc}; no
 * fechamento acrescenta {@code status} e {@code durationMs} e emite uma linha de
 * "access log" (DEBUG quando OK; WARN quando erro, para que prod — em INFO —
 * ainda enxergue falhas).
 *
 * <p>O MDC do Quarkus é respaldado pelo contexto (Vert.x duplicated context) da
 * chamada: os campos definidos aqui propagam para a cadeia Mutiny do serviço e
 * ficam isolados por requisição automaticamente.
 */
@ApplicationScoped
@GlobalInterceptor
public class LoggingInterceptor implements ServerInterceptor {

    private static final Logger LOG = Logger.getLogger(LoggingInterceptor.class);

    private static final Metadata.Key<String> TRACEPARENT =
            Metadata.Key.of("traceparent", Metadata.ASCII_STRING_MARSHALLER);
    private static final Metadata.Key<String> X_REQUEST_ID =
            Metadata.Key.of("x-request-id", Metadata.ASCII_STRING_MARSHALLER);

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            final ServerCall<ReqT, RespT> call,
            final Metadata headers,
            final ServerCallHandler<ReqT, RespT> next) {

        final String requestId = resolveRequestId(headers);
        final String rpc = call.getMethodDescriptor().getFullMethodName();
        final long startNanos = System.nanoTime();

        MDC.put(LogFields.REQUEST_ID, requestId);
        MDC.put(LogFields.RPC, rpc);
        LOG.debug("chamada gRPC recebida");

        // Envolve a chamada para capturar o status final e medir a latência total
        // no momento do fechamento (close).
        final ServerCall<ReqT, RespT> observed =
                new ForwardingServerCall.SimpleForwardingServerCall<>(call) {
                    @Override
                    public void close(final Status status, final Metadata trailers) {
                        final long durationMs = (System.nanoTime() - startNanos) / 1_000_000L;
                        MDC.put(LogFields.STATUS, status.getCode().name());
                        MDC.put(LogFields.DURATION_MS, Long.toString(durationMs));
                        if (status.isOk()) {
                            LOG.debug("chamada gRPC concluída");
                        } else {
                            LOG.warnf("chamada gRPC concluída com erro: %s", status.getDescription());
                        }
                        super.close(status, trailers);
                    }
                };

        return next.startCall(observed, headers);
    }

    /**
     * Resolve o id de correlação com a precedência: {@code traceparent}
     * (trace-id do W3C) → {@code x-request-id} → UUID gerado.
     */
    private static String resolveRequestId(final Metadata headers) {
        final String traceparent = headers.get(TRACEPARENT);
        if (traceparent != null) {
            // Formato W3C: "00-<trace-id>-<span-id>-<flags>"; usamos o trace-id.
            final String[] parts = traceparent.split("-");
            if (parts.length >= 2 && !parts[1].isBlank()) {
                return parts[1];
            }
        }
        final String requestId = headers.get(X_REQUEST_ID);
        if (requestId != null && !requestId.isBlank()) {
            return requestId;
        }
        return UUID.randomUUID().toString();
    }
}
