package io.github.claudineyns.redis.grpc.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.claudineyns.redis.grpc.v1.GetRequest;
import io.github.claudineyns.redis.grpc.v1.GetResponse;
import io.github.claudineyns.redis.grpc.v1.StringService;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.quarkus.grpc.GrpcClient;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.mutiny.redis.client.Command;
import io.vertx.mutiny.redis.client.Redis;
import io.vertx.mutiny.redis.client.Request;
import jakarta.inject.Inject;

@QuarkusTest
class StringGrpcServiceTest {

    @GrpcClient("strings")
    StringService client;

    @Inject
    Redis redis; // semeia chaves direto no Redis para preparar o cenário

    @Test
    void getReturnsStoredValue() {
        final String key = "test:greeting";
        redis.send(Request.cmd(Command.SET).arg(key).arg("hello")).await().indefinitely();

        final GetResponse response = client.get(
                GetRequest.newBuilder().setKey(key).build()).await().indefinitely();

        assertTrue(response.hasValue());
        assertEquals("hello", response.getValue().toStringUtf8());
    }

    @Test
    void getMissingKeyReturnsNoValue() {
        final GetResponse response = client.get(
                GetRequest.newBuilder().setKey("test:absent").build()).await().indefinitely();

        assertFalse(response.hasValue());
    }

    @Test
    void getOnWrongTypeFails() {
        final String key = "test:hashkey";
        redis.send(Request.cmd(Command.HSET).arg(key).arg("f").arg("v")).await().indefinitely();

        final StatusRuntimeException failure = assertThrows(StatusRuntimeException.class, () ->
                client.get(GetRequest.newBuilder().setKey(key).build()).await().indefinitely());

        assertEquals(Status.Code.FAILED_PRECONDITION, failure.getStatus().getCode());
    }
}
