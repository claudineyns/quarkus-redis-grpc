package io.github.claudineyns.redis.grpc.server;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.quarkus.grpc.GlobalInterceptor;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Interceptor de autenticação (DESIGN seção 6.1): valida o par
 * ACCESS_KEY/SECRET_KEY trafegado na metadata gRPC. Global → cobre TODOS os
 * serviços, inclusive o Server Reflection (decisão da seção 6).
 *
 * <p>Quando a validação está desabilitada (sem chave mestra), passa direto.
 * Caso contrário, credencial ausente/inválida → {@code UNAUTHENTICATED}.
 */
@ApplicationScoped
@GlobalInterceptor
public class AuthInterceptor implements ServerInterceptor {

    private static final Logger LOG = Logger.getLogger(AuthInterceptor.class);

    @Inject
    CredentialValidator validator;

    @ConfigProperty(name = "proxy.auth.access-key-header", defaultValue = "x-grpc-access-key")
    String accessKeyHeader;

    @ConfigProperty(name = "proxy.auth.secret-key-header", defaultValue = "x-grpc-secret-key")
    String secretKeyHeader;

    private Metadata.Key<String> accessKey;
    private Metadata.Key<String> secretKey;

    @PostConstruct
    void init() {
        this.accessKey = Metadata.Key.of(accessKeyHeader, Metadata.ASCII_STRING_MARSHALLER);
        this.secretKey = Metadata.Key.of(secretKeyHeader, Metadata.ASCII_STRING_MARSHALLER);
        if (validator.isEnabled()) {
            LOG.infof("Auth gRPC ativa (headers: %s / %s).", accessKeyHeader, secretKeyHeader);
        }
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            final ServerCall<ReqT, RespT> call,
            final Metadata headers,
            final ServerCallHandler<ReqT, RespT> next) {

        if (!validator.isEnabled()) {
            return next.startCall(call, headers);
        }

        final String providedAccess = headers.get(accessKey);
        if (!validator.isValid(providedAccess, headers.get(secretKey))) {
            // Log diagnóstico server-side (sem expor valores). A resposta ao
            // cliente segue genérica (não distingue ausente de inválido — oráculo).
            LOG.warnf("Acesso NEGADO (credenciais %s) rpc=%s",
                    (providedAccess == null || providedAccess.isBlank()) ? "ausentes" : "inválidas",
                    call.getMethodDescriptor().getFullMethodName());
            call.close(Status.UNAUTHENTICATED.withDescription("credenciais inválidas ou ausentes"),
                    new Metadata());
            return new ServerCall.Listener<>() {
            };
        }
        return next.startCall(call, headers);
    }
}
