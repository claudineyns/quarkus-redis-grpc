package io.github.claudineyns.redis.grpc.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.claudineyns.redis.grpc.v1.GetRequest;
import io.github.claudineyns.redis.grpc.v1.StringService;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.quarkus.grpc.GrpcClient;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;

/**
 * Auth de credenciais com a validação HABILITADA (via @TestProfile). Os demais
 * testes rodam com auth desabilitada (sem chave mestra) e não são afetados.
 *
 * <p>Vetor cripto gerado por openssl (oracle independente) — cruza a impl Java
 * de HMAC-SHA256/SHA-256 com a definição do DESIGN 6.1.
 */
@QuarkusTest
@TestProfile(AuthTest.AuthEnabled.class)
class AuthTest {

    static final String MASTER = "7b68a1658d06027c5382df258507bd517053d23fe8e5d8dd3ad512ea1ac24289";
    static final String ACCESS = "fe098441569f260304ed7eaa67ead611";
    static final String SECRET = "6c361f5bf5a3b538acc029d78bc32205123bac888e8b84fb126c32b62a789426";
    static final String HASH = "d99492b6fe06299f3c41a9aae1e345bd9188b0c576427706ed3d68ddb2465346";

    public static class AuthEnabled implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "proxy.auth.master-key", MASTER,
                    "proxy.auth.access-key-hashes", HASH);
        }
    }

    @Inject
    CredentialValidator validator;

    @GrpcClient("strings")
    StringService client;

    @Test
    void validatorAcceptsValidPair() {
        assertTrue(validator.isValid(ACCESS, SECRET));
    }

    @Test
    void validatorRejectsWrongSecret() {
        assertFalse(validator.isValid(ACCESS, "0".repeat(64)));
    }

    @Test
    void validatorRejectsUnknownAccessKey() {
        assertFalse(validator.isValid("f".repeat(32), SECRET));
    }

    @Test
    void validatorRejectsBlank() {
        assertFalse(validator.isValid("", ""));
    }

    @Test
    void interceptorRejectsCallWithoutCredentials() {
        final StatusRuntimeException failure = assertThrows(StatusRuntimeException.class, () ->
                client.get(GetRequest.newBuilder().setKey("auth:none").build()).await().indefinitely());
        assertEquals(Status.Code.UNAUTHENTICATED, failure.getStatus().getCode());
    }
}
