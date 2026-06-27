package io.github.claudineyns.redis.grpc.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import io.github.claudineyns.redis.grpc.v1.DelRequest;
import io.github.claudineyns.redis.grpc.v1.ExistsRequest;
import io.github.claudineyns.redis.grpc.v1.KeyCount;
import io.github.claudineyns.redis.grpc.v1.KeyService;
import io.github.claudineyns.redis.grpc.v1.KeyType;
import io.github.claudineyns.redis.grpc.v1.TypeRequest;
import io.github.claudineyns.redis.grpc.v1.UnlinkRequest;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.quarkus.grpc.GrpcClient;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.mutiny.redis.client.Command;
import io.vertx.mutiny.redis.client.Redis;
import io.vertx.mutiny.redis.client.Request;
import jakarta.inject.Inject;

@QuarkusTest
class KeyGrpcServiceTest {

    @GrpcClient("keys")
    KeyService client;

    @Inject
    Redis redis;

    @Test
    void delRemovesKeysAndReturnsCount() {
        seed("test:key:d1", "v");
        seed("test:key:d2", "v");

        final KeyCount response = client.del(DelRequest.newBuilder()
                .addKeys("test:key:d1").addKeys("test:key:d2").addKeys("test:key:dmissing")
                .build()).await().indefinitely();

        assertEquals(2L, response.getCount());
        assertEquals(0L, rawExists("test:key:d1"));
    }

    @Test
    void unlinkRemovesKeysAndReturnsCount() {
        seed("test:key:u1", "v");

        final KeyCount response = client.unlink(UnlinkRequest.newBuilder()
                .addKeys("test:key:u1").build()).await().indefinitely();

        assertEquals(1L, response.getCount());
        assertEquals(0L, rawExists("test:key:u1"));
    }

    @Test
    void existsCountsExisting() {
        seed("test:key:e1", "v");

        final KeyCount response = client.exists(ExistsRequest.newBuilder()
                .addKeys("test:key:e1").addKeys("test:key:emissing").build())
                .await().indefinitely();

        assertEquals(1L, response.getCount());
    }

    @Test
    void delEmptyRejected() {
        final StatusRuntimeException failure = assertThrows(StatusRuntimeException.class, () ->
                client.del(DelRequest.newBuilder().build()).await().indefinitely());
        assertEquals(Status.Code.INVALID_ARGUMENT, failure.getStatus().getCode());
    }

    @Test
    void typeReturnsString() {
        seed("test:key:ts", "v");
        final KeyType response = client.type(
                TypeRequest.newBuilder().setKey("test:key:ts").build()).await().indefinitely();
        assertEquals("string", response.getType());
    }

    @Test
    void typeReturnsHash() {
        redis.send(Request.cmd(Command.HSET).arg("test:key:th").arg("f").arg("v"))
                .await().indefinitely();
        final KeyType response = client.type(
                TypeRequest.newBuilder().setKey("test:key:th").build()).await().indefinitely();
        assertEquals("hash", response.getType());
    }

    @Test
    void typeOnAbsentReturnsNone() {
        final KeyType response = client.type(
                TypeRequest.newBuilder().setKey("test:key:tabsent").build()).await().indefinitely();
        assertEquals("none", response.getType());
    }

    // ---------- helpers ----------

    private void seed(final String key, final String value) {
        redis.send(Request.cmd(Command.SET).arg(key).arg(value)).await().indefinitely();
    }

    private long rawExists(final String key) {
        return redis.send(Request.cmd(Command.EXISTS).arg(key)).await().indefinitely().toLong();
    }
}
