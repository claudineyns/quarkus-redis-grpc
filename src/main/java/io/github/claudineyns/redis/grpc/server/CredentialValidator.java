package io.github.claudineyns.redis.grpc.server;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Validador de credenciais de acesso (DESIGN seção 6.1).
 *
 * <p>Regra: o par é válido quando {@code SHA-256(ACCESS_KEY) ∈ allowlist} E
 * {@code HMAC-SHA256(chave_mestra, ACCESS_KEY) == SECRET_KEY} (comparação em
 * tempo constante). A chave mestra é a chave do HMAC (bytes crus, hex-decoded);
 * a mensagem é a string ACCESS_KEY.
 *
 * <p>Habilitado apenas quando {@code proxy.auth.master-key} está presente; caso
 * contrário, a validação fica desativada (dev/test sem credenciais).
 */
@ApplicationScoped
public class CredentialValidator {

    private static final Logger LOG = Logger.getLogger(CredentialValidator.class);
    private static final String HMAC_ALG = "HmacSHA256";
    private static final String SHA_256 = "SHA-256";

    // defaultValue="" → ausente vira Optional.empty() no MicroProfile/Quarkus.
    @ConfigProperty(name = "proxy.auth.master-key", defaultValue = "")
    Optional<String> masterKey;

    @ConfigProperty(name = "proxy.auth.access-key-hashes", defaultValue = "")
    Optional<List<String>> accessKeyHashes;

    private boolean enabled;
    private byte[] masterKeyBytes;
    private Set<String> allowlist;

    @PostConstruct
    void init() {
        final String key = masterKey.map(String::trim).orElse("");
        this.enabled = !key.isEmpty();
        this.allowlist = accessKeyHashes.orElseGet(List::of).stream()
                .map(String::trim).filter(h -> !h.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
        if (!enabled) {
            LOG.warn("Validação de credenciais DESABILITADA (proxy.auth.master-key ausente).");
            return;
        }
        this.masterKeyBytes = HexFormat.of().parseHex(key);
        LOG.infof("Validação de credenciais habilitada (%d access key(s) na allowlist).",
                allowlist.size());
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * true se o par for válido: allowlist contém o SHA-256 do access key E o
     * secret bate com o HMAC-SHA256 (tempo constante).
     */
    public boolean isValid(final String accessKey, final String secretKey) {
        if (accessKey == null || accessKey.isBlank() || secretKey == null || secretKey.isBlank()) {
            return false;
        }
        if (!allowlist.contains(sha256Hex(accessKey))) {
            return false;
        }
        final byte[] expected = hmacHex(accessKey).getBytes(StandardCharsets.US_ASCII);
        final byte[] provided = secretKey.trim().getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, provided);
    }

    private String hmacHex(final String message) {
        try {
            final Mac mac = Mac.getInstance(HMAC_ALG);
            mac.init(new SecretKeySpec(masterKeyBytes, HMAC_ALG));
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (final Exception e) {
            throw new IllegalStateException("falha ao calcular HMAC", e);
        }
    }

    private static String sha256Hex(final String value) {
        try {
            final MessageDigest digest = MessageDigest.getInstance(SHA_256);
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponível", e);
        }
    }
}
