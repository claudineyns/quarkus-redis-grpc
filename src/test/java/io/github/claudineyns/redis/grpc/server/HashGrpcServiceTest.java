package io.github.claudineyns.redis.grpc.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.google.protobuf.ByteString;

import io.github.claudineyns.redis.grpc.v1.FieldValue;
import io.github.claudineyns.redis.grpc.v1.HDelRequest;
import io.github.claudineyns.redis.grpc.v1.HExistsRequest;
import io.github.claudineyns.redis.grpc.v1.HGetAllRequest;
import io.github.claudineyns.redis.grpc.v1.HGetRequest;
import io.github.claudineyns.redis.grpc.v1.HKeysRequest;
import io.github.claudineyns.redis.grpc.v1.HLenRequest;
import io.github.claudineyns.redis.grpc.v1.HMGetRequest;
import io.github.claudineyns.redis.grpc.v1.HMGetResponse;
import io.github.claudineyns.redis.grpc.v1.HSetRequest;
import io.github.claudineyns.redis.grpc.v1.HValsRequest;
import io.github.claudineyns.redis.grpc.v1.HashEntries;
import io.github.claudineyns.redis.grpc.v1.HashService;
import io.github.claudineyns.redis.grpc.v1.HashValue;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.quarkus.grpc.GrpcClient;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.mutiny.redis.client.Command;
import io.vertx.mutiny.redis.client.Redis;
import io.vertx.mutiny.redis.client.Request;
import jakarta.inject.Inject;

@QuarkusTest
class HashGrpcServiceTest {

    @GrpcClient("hashes")
    HashService client;

    @Inject
    Redis redis;

    private static FieldValue fv(final String field, final String value) {
        return FieldValue.newBuilder().setField(field)
                .setValue(ByteString.copyFromUtf8(value)).build();
    }

    @Test
    void hSetCountsNewFieldsAndUpdates() {
        del("test:hash:set");
        final long created = client.hSet(HSetRequest.newBuilder().setKey("test:hash:set")
                .addFields(fv("f1", "v1")).addFields(fv("f2", "v2"))
                .build()).await().indefinitely().getCount();
        assertEquals(2L, created);
        // atualizar f1 + novo f3 → apenas 1 novo
        final long mixed = client.hSet(HSetRequest.newBuilder().setKey("test:hash:set")
                .addFields(fv("f1", "v1b")).addFields(fv("f3", "v3"))
                .build()).await().indefinitely().getCount();
        assertEquals(1L, mixed);
    }

    @Test
    void hGetReturnsValueBinarySafe() {
        del("test:hash:get");
        client.hSet(HSetRequest.newBuilder().setKey("test:hash:get")
                .addFields(fv("f", "valor")).build()).await().indefinitely();
        final HashValue response = client.hGet(HGetRequest.newBuilder()
                .setKey("test:hash:get").setField("f").build()).await().indefinitely();
        assertTrue(response.hasValue());
        assertEquals("valor", response.getValue().toStringUtf8());
    }

    @Test
    void hGetAbsentFieldHasNoValue() {
        del("test:hash:get-absent");
        final HashValue response = client.hGet(HGetRequest.newBuilder()
                .setKey("test:hash:get-absent").setField("nope").build()).await().indefinitely();
        assertFalse(response.hasValue());
    }

    @Test
    void hDelReturnsRemovedCount() {
        del("test:hash:del");
        client.hSet(HSetRequest.newBuilder().setKey("test:hash:del")
                .addFields(fv("a", "1")).addFields(fv("b", "2")).build()).await().indefinitely();
        final long removed = client.hDel(HDelRequest.newBuilder().setKey("test:hash:del")
                .addFields("a").addFields("z").build()).await().indefinitely().getCount();
        assertEquals(1L, removed);
    }

    @Test
    void hExistsTrueAndFalse() {
        del("test:hash:ex");
        client.hSet(HSetRequest.newBuilder().setKey("test:hash:ex")
                .addFields(fv("f", "v")).build()).await().indefinitely();
        assertTrue(client.hExists(HExistsRequest.newBuilder()
                .setKey("test:hash:ex").setField("f").build()).await().indefinitely().getExists());
        assertFalse(client.hExists(HExistsRequest.newBuilder()
                .setKey("test:hash:ex").setField("z").build()).await().indefinitely().getExists());
    }

    @Test
    void hLenCountsFields() {
        del("test:hash:len");
        client.hSet(HSetRequest.newBuilder().setKey("test:hash:len")
                .addFields(fv("a", "1")).addFields(fv("b", "2")).addFields(fv("c", "3"))
                .build()).await().indefinitely();
        assertEquals(3L, client.hLen(HLenRequest.newBuilder().setKey("test:hash:len").build())
                .await().indefinitely().getCount());
    }

    @Test
    void hSetEmptyRejected() {
        final StatusRuntimeException failure = assertThrows(StatusRuntimeException.class, () ->
                client.hSet(HSetRequest.newBuilder().setKey("test:hash:empty").build())
                        .await().indefinitely());
        assertEquals(Status.Code.INVALID_ARGUMENT, failure.getStatus().getCode());
    }

    @Test
    void hSetOnWrongTypeFailsPrecondition() {
        del("test:hash:wrong");
        redis.send(Request.cmd(Command.SET).arg("test:hash:wrong").arg("v")).await().indefinitely();
        final StatusRuntimeException failure = assertThrows(StatusRuntimeException.class, () ->
                client.hSet(HSetRequest.newBuilder().setKey("test:hash:wrong")
                        .addFields(fv("f", "v")).build()).await().indefinitely());
        assertEquals(Status.Code.FAILED_PRECONDITION, failure.getStatus().getCode());
    }

    // ---------- múltiplos / listagem (Fatia 2) ----------

    @Test
    void hMGetAlignedToInputWithNils() {
        del("test:hash:mget");
        client.hSet(HSetRequest.newBuilder().setKey("test:hash:mget")
                .addFields(fv("a", "1")).addFields(fv("c", "3")).build()).await().indefinitely();
        final HMGetResponse response = client.hMGet(HMGetRequest.newBuilder()
                .setKey("test:hash:mget").addFields("a").addFields("b").addFields("c")
                .build()).await().indefinitely();
        assertEquals(3, response.getValuesCount());
        assertTrue(response.getValues(0).hasValue());
        assertEquals("1", response.getValues(0).getValue().toStringUtf8());
        assertFalse(response.getValues(1).hasValue()); // b ausente -> nil
        assertEquals("3", response.getValues(2).getValue().toStringUtf8());
    }

    @Test
    void hMGetEmptyRejected() {
        final StatusRuntimeException failure = assertThrows(StatusRuntimeException.class, () ->
                client.hMGet(HMGetRequest.newBuilder().setKey("test:hash:mget-empty").build())
                        .await().indefinitely());
        assertEquals(Status.Code.INVALID_ARGUMENT, failure.getStatus().getCode());
    }

    @Test
    void hGetAllReturnsAllPairs() {
        del("test:hash:all");
        client.hSet(HSetRequest.newBuilder().setKey("test:hash:all")
                .addFields(fv("a", "1")).addFields(fv("b", "2")).build()).await().indefinitely();
        final HashEntries response = client.hGetAll(HGetAllRequest.newBuilder()
                .setKey("test:hash:all").build()).await().indefinitely();
        assertEquals(2, response.getEntriesCount());
        final java.util.Map<String, String> map = new java.util.HashMap<>();
        response.getEntriesList().forEach(e -> map.put(e.getField(), e.getValue().toStringUtf8()));
        assertEquals(java.util.Map.of("a", "1", "b", "2"), map);
    }

    @Test
    void hGetAllAbsentReturnsEmpty() {
        del("test:hash:all-absent");
        final HashEntries response = client.hGetAll(HGetAllRequest.newBuilder()
                .setKey("test:hash:all-absent").build()).await().indefinitely();
        assertEquals(0, response.getEntriesCount());
    }

    @Test
    void hKeysAndHVals() {
        del("test:hash:kv");
        client.hSet(HSetRequest.newBuilder().setKey("test:hash:kv")
                .addFields(fv("a", "1")).addFields(fv("b", "2")).build()).await().indefinitely();
        assertEquals(java.util.Set.of("a", "b"),
                new java.util.HashSet<>(client.hKeys(HKeysRequest.newBuilder()
                        .setKey("test:hash:kv").build()).await().indefinitely().getFieldsList()));
        final java.util.Set<String> vals = new java.util.HashSet<>();
        client.hVals(HValsRequest.newBuilder().setKey("test:hash:kv").build())
                .await().indefinitely().getValuesList()
                .forEach(v -> vals.add(v.toStringUtf8()));
        assertEquals(java.util.Set.of("1", "2"), vals);
    }

    // ---------- helpers ----------

    private void del(final String key) {
        redis.send(Request.cmd(Command.DEL).arg(key)).await().indefinitely();
    }
}
