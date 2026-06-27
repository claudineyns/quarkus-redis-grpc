package io.github.claudineyns.redis.grpc.server;

import org.jboss.logging.Logger;
import org.jboss.logging.MDC;

import com.google.protobuf.ByteString;

import io.github.claudineyns.redis.grpc.v1.GetRequest;
import io.github.claudineyns.redis.grpc.v1.GetResponse;
import io.github.claudineyns.redis.grpc.v1.SetCondition;
import io.github.claudineyns.redis.grpc.v1.SetRequest;
import io.github.claudineyns.redis.grpc.v1.SetResponse;
import io.github.claudineyns.redis.grpc.v1.StringService;
import io.quarkus.grpc.GrpcService;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.redis.client.Redis;
import io.vertx.mutiny.redis.client.Request;
import io.vertx.mutiny.redis.client.Response;
import io.vertx.mutiny.redis.client.Command;
import jakarta.inject.Inject;

/**
 * Família KEY/VALUE — implementação gRPC do {@code StringService}.
 *
 * <p>Tradução 1:1 de comandos Redis (DESIGN seção 2). Fatia atual: GET.
 */
@GrpcService
public class StringGrpcService implements StringService {

    private static final Logger LOG = Logger.getLogger(StringGrpcService.class);

    private static final String CMD_GET = "GET";
    private static final String CMD_SET = "SET";

    // Tokens de opção do comando SET (constantes — premissa S1192).
    private static final String OPT_EX = "EX";
    private static final String OPT_PX = "PX";
    private static final String OPT_EXAT = "EXAT";
    private static final String OPT_PXAT = "PXAT";
    private static final String OPT_KEEPTTL = "KEEPTTL";
    private static final String OPT_NX = "NX";
    private static final String OPT_XX = "XX";
    private static final String OPT_GET = "GET";

    @Inject
    Redis redis; // CDI: não pode ser final (exceção da convenção de DESIGN seção 10)

    @Override
    public Uni<GetResponse> get(final GetRequest request) {
        // Metadados desta operação no MDC (propagam pela cadeia Mutiny e saem nas
        // linhas de log). Valores nunca vão ao log; a chave, sim (DESIGN 8.1).
        MDC.put(LogFields.COMMAND, CMD_GET);
        MDC.put(LogFields.KEY, request.getKey());
        LOG.debug("GET recebido");

        // Monta o comando Redis "GET <key>" como Request de baixo nível. Usamos
        // Redis + Request (e não a RedisAPI tipada como String) para preservar o
        // binary-safe dos valores — ver DESIGN seção 4.
        final Request command = Request.cmd(Command.GET).arg(request.getKey());
        final long startNanos = System.nanoTime();

        // redis.send devolve um Uni<Response> (não-bloqueante). Encadeamos:
        //  - map: mede a latência do Redis, traduz a Response para o GetResponse
        //    e loga a conclusão;
        //  - onFailure: um erro RESP (ex.: WRONGTYPE) ou falha de infra é
        //    convertido em status gRPC. Resultado normal — inclusive nil — NÃO
        //    passa por aqui; nil é sucesso e sai no payload (DESIGN seção 5.1).
        return redis.send(command)
                .map(response -> {
                    MDC.put(LogFields.REDIS_DURATION_MS,
                            Long.toString((System.nanoTime() - startNanos) / 1_000_000L));
                    final GetResponse result = toGetResponse(response);
                    LOG.debugf("GET concluído (found=%s)", result.hasValue());
                    return result;
                })
                .onFailure().transform(RedisErrors::toStatus);
    }

    @Override
    public Uni<SetResponse> set(final SetRequest request) {
        MDC.put(LogFields.COMMAND, CMD_SET);
        MDC.put(LogFields.KEY, request.getKey());
        LOG.debug("SET recebido");

        final Request command = buildSetCommand(request);
        // Guardamos condição e flag GET porque deles depende a interpretação da
        // resposta (ver toSetResponse).
        final boolean getRequested = request.getGet();
        final SetCondition condition = request.getCondition();
        final long startNanos = System.nanoTime();

        return redis.send(command)
                .map(response -> {
                    MDC.put(LogFields.REDIS_DURATION_MS,
                            Long.toString((System.nanoTime() - startNanos) / 1_000_000L));
                    final SetResponse result = toSetResponse(response, getRequested, condition);
                    LOG.debugf("SET concluído (applied=%s)", result.getApplied());
                    return result;
                })
                .onFailure().transform(RedisErrors::toStatus);
    }

    /**
     * Monta o comando "SET key value [EX|PX|EXAT|PXAT|KEEPTTL] [NX|XX] [GET]" na
     * ordem canônica do Redis. O valor vai como bytes crus (binary-safe).
     */
    private static Request buildSetCommand(final SetRequest request) {
        final Request command = Request.cmd(Command.SET)
                .arg(request.getKey())
                .arg(request.getValue().toByteArray());

        switch (request.getExpirationCase()) {
            case EX_SECONDS -> command.arg(OPT_EX).arg(request.getExSeconds());
            case PX_MILLIS -> command.arg(OPT_PX).arg(request.getPxMillis());
            case EXAT_UNIX_SECONDS -> command.arg(OPT_EXAT).arg(request.getExatUnixSeconds());
            case PXAT_UNIX_MILLIS -> command.arg(OPT_PXAT).arg(request.getPxatUnixMillis());
            case KEEP_TTL -> {
                if (request.getKeepTtl()) {
                    command.arg(OPT_KEEPTTL);
                }
            }
            case EXPIRATION_NOT_SET -> { /* sem expiração */ }
        }

        switch (request.getCondition()) {
            case SET_CONDITION_NX -> command.arg(OPT_NX);
            case SET_CONDITION_XX -> command.arg(OPT_XX);
            default -> { /* sem condição (UNSPECIFIED/UNRECOGNIZED) */ }
        }

        if (request.getGet()) {
            command.arg(OPT_GET);
        }
        return command;
    }

    /**
     * Traduz a resposta do SET para o contrato. Sutileza (ver discussão):
     * <ul>
     *   <li>sem GET: a resposta é "OK" (sucesso) ou nil (NX/XX barrou) →
     *       {@code applied = resposta != nil}; sem {@code previous};</li>
     *   <li>com GET: a resposta é o valor antigo (ou nil) → vira {@code previous};
     *       e {@code applied} é deduzido pela condição, pois o Redis não informa
     *       explicitamente se gravou.</li>
     * </ul>
     */
    private static SetResponse toSetResponse(final Response response,
            final boolean getRequested, final SetCondition condition) {
        final SetResponse.Builder builder = SetResponse.newBuilder();

        if (getRequested) {
            if (response != null) { // null = nil (chave não existia antes)
                builder.setPrevious(ByteString.copyFrom(response.toBytes()));
            }
            builder.setApplied(switch (condition) {
                case SET_CONDITION_NX -> response == null; // gravou só se NÃO existia
                case SET_CONDITION_XX -> response != null; // gravou só se existia
                default -> true;                           // SET incondicional sempre grava
            });
        } else {
            // Sem GET, nil só acontece quando NX/XX impede a gravação.
            builder.setApplied(response != null);
        }
        return builder.build();
    }

    private static GetResponse toGetResponse(final Response response) {
        final GetResponse.Builder builder = GetResponse.newBuilder();
        // No cliente Vert.x, uma resposta bulk nil do Redis chega como Response
        // null. Distinguimos "chave inexistente" (nil → value ausente) de "string
        // vazia" (value presente, porém vazio) deixando o campo optional não-setado
        // quando null. toBytes() preserva os bytes crus (binary-safe).
        if (response != null) {
            builder.setValue(ByteString.copyFrom(response.toBytes()));
        }
        return builder.build();
    }
}
