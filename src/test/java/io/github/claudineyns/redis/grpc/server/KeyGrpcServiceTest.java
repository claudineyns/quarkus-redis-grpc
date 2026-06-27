package io.github.claudineyns.redis.grpc.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.claudineyns.redis.grpc.v1.DelRequest;
import io.github.claudineyns.redis.grpc.v1.ExistsRequest;
import io.github.claudineyns.redis.grpc.v1.ExpireAtRequest;
import io.github.claudineyns.redis.grpc.v1.ExpireCondition;
import io.github.claudineyns.redis.grpc.v1.ExpireRequest;
import io.github.claudineyns.redis.grpc.v1.KeyChange;
import io.github.claudineyns.redis.grpc.v1.KeyCount;
import io.github.claudineyns.redis.grpc.v1.KeyService;
import io.github.claudineyns.redis.grpc.v1.KeyType;
import io.github.claudineyns.redis.grpc.v1.PExpireAtRequest;
import io.github.claudineyns.redis.grpc.v1.PExpireRequest;
import io.github.claudineyns.redis.grpc.v1.PTtlRequest;
import io.github.claudineyns.redis.grpc.v1.PersistRequest;
import io.github.claudineyns.redis.grpc.v1.TtlRequest;
import io.github.claudineyns.redis.grpc.v1.TtlValue;
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

    // ---------- expiração (Fatia 2) ----------

    @Test
    void expireSetsTtl() {
        seed("test:k:exp", "v");
        final KeyChange r = client.expire(ExpireRequest.newBuilder()
                .setKey("test:k:exp").setSeconds(100).build()).await().indefinitely();
        assertTrue(r.getApplied());
        assertTrue(rawTtl("test:k:exp") > 0);
    }

    @Test
    void expireOnAbsentReturnsFalse() {
        final KeyChange r = client.expire(ExpireRequest.newBuilder()
                .setKey("test:k:exp-absent").setSeconds(100).build()).await().indefinitely();
        assertFalse(r.getApplied());
    }

    @Test
    void pExpireSetsTtl() {
        seed("test:k:pexp", "v");
        final KeyChange r = client.pExpire(PExpireRequest.newBuilder()
                .setKey("test:k:pexp").setMillis(100_000).build()).await().indefinitely();
        assertTrue(r.getApplied());
        assertTrue(rawTtl("test:k:pexp") > 0);
    }

    @Test
    void expireAtSetsTtl() {
        seed("test:k:expat", "v");
        final long futureSec = System.currentTimeMillis() / 1000L + 100L;
        final KeyChange r = client.expireAt(ExpireAtRequest.newBuilder()
                .setKey("test:k:expat").setUnixSeconds(futureSec).build()).await().indefinitely();
        assertTrue(r.getApplied());
        assertTrue(rawTtl("test:k:expat") > 0);
    }

    @Test
    void pExpireAtSetsTtl() {
        seed("test:k:pexpat", "v");
        final long futureMs = System.currentTimeMillis() + 100_000L;
        final KeyChange r = client.pExpireAt(PExpireAtRequest.newBuilder()
                .setKey("test:k:pexpat").setUnixMillis(futureMs).build()).await().indefinitely();
        assertTrue(r.getApplied());
        assertTrue(rawTtl("test:k:pexpat") > 0);
    }

    @Test
    void persistRemovesTtl() {
        seedWithTtl("test:k:persist", 100);
        final KeyChange r = client.persist(PersistRequest.newBuilder()
                .setKey("test:k:persist").build()).await().indefinitely();
        assertTrue(r.getApplied());
        assertEquals(-1L, rawTtl("test:k:persist"));
    }

    @Test
    void persistWithoutTtlReturnsFalse() {
        seed("test:k:persist-none", "v");
        final KeyChange r = client.persist(PersistRequest.newBuilder()
                .setKey("test:k:persist-none").build()).await().indefinitely();
        assertFalse(r.getApplied());
    }

    @Test
    void ttlReturnsRemaining() {
        seedWithTtl("test:k:ttl", 100);
        final TtlValue r = client.ttl(TtlRequest.newBuilder()
                .setKey("test:k:ttl").build()).await().indefinitely();
        assertTrue(r.getValue() > 0);
    }

    @Test
    void ttlNoExpireReturnsMinusOne() {
        seed("test:k:ttl-none", "v");
        final TtlValue r = client.ttl(TtlRequest.newBuilder()
                .setKey("test:k:ttl-none").build()).await().indefinitely();
        assertEquals(-1L, r.getValue());
    }

    @Test
    void ttlAbsentReturnsMinusTwo() {
        final TtlValue r = client.ttl(TtlRequest.newBuilder()
                .setKey("test:k:ttl-absent").build()).await().indefinitely();
        assertEquals(-2L, r.getValue());
    }

    @Test
    void pTtlReturnsRemaining() {
        seedWithTtl("test:k:pttl", 100);
        final TtlValue r = client.pTtl(PTtlRequest.newBuilder()
                .setKey("test:k:pttl").build()).await().indefinitely();
        assertTrue(r.getValue() > 0);
    }

    @Test
    void expireNxAppliesOnlyWithoutTtl() {
        seed("test:k:nx", "v"); // sem TTL
        assertTrue(client.expire(ExpireRequest.newBuilder().setKey("test:k:nx")
                .setSeconds(100).setCondition(ExpireCondition.EXPIRE_CONDITION_NX).build())
                .await().indefinitely().getApplied());
        // agora já tem TTL → NX não aplica
        assertFalse(client.expire(ExpireRequest.newBuilder().setKey("test:k:nx")
                .setSeconds(200).setCondition(ExpireCondition.EXPIRE_CONDITION_NX).build())
                .await().indefinitely().getApplied());
    }

    @Test
    void expireXxAppliesOnlyWithTtl() {
        seed("test:k:xx", "v"); // sem TTL → XX não aplica
        assertFalse(client.expire(ExpireRequest.newBuilder().setKey("test:k:xx")
                .setSeconds(100).setCondition(ExpireCondition.EXPIRE_CONDITION_XX).build())
                .await().indefinitely().getApplied());
        seedWithTtl("test:k:xx", 100); // agora com TTL → XX aplica
        assertTrue(client.expire(ExpireRequest.newBuilder().setKey("test:k:xx")
                .setSeconds(200).setCondition(ExpireCondition.EXPIRE_CONDITION_XX).build())
                .await().indefinitely().getApplied());
    }

    @Test
    void expireGtAppliesOnlyWhenGreater() {
        seedWithTtl("test:k:gt", 100);
        assertFalse(client.expire(ExpireRequest.newBuilder().setKey("test:k:gt")
                .setSeconds(50).setCondition(ExpireCondition.EXPIRE_CONDITION_GT).build())
                .await().indefinitely().getApplied()); // 50 < 100 → não
        assertTrue(client.expire(ExpireRequest.newBuilder().setKey("test:k:gt")
                .setSeconds(500).setCondition(ExpireCondition.EXPIRE_CONDITION_GT).build())
                .await().indefinitely().getApplied()); // 500 > 100 → sim
    }

    // ---------- helpers ----------

    private void seed(final String key, final String value) {
        redis.send(Request.cmd(Command.SET).arg(key).arg(value)).await().indefinitely();
    }

    private void seedWithTtl(final String key, final long seconds) {
        redis.send(Request.cmd(Command.SET).arg(key).arg("v").arg("EX").arg(Long.toString(seconds)))
                .await().indefinitely();
    }

    private long rawExists(final String key) {
        return redis.send(Request.cmd(Command.EXISTS).arg(key)).await().indefinitely().toLong();
    }

    private long rawTtl(final String key) {
        return redis.send(Request.cmd(Command.TTL).arg(key)).await().indefinitely().toLong();
    }
}
