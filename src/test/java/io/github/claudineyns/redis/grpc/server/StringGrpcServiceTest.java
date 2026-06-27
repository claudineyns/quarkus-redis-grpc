package io.github.claudineyns.redis.grpc.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.google.protobuf.ByteString;

import io.github.claudineyns.redis.grpc.v1.GetRequest;
import io.github.claudineyns.redis.grpc.v1.GetResponse;
import io.github.claudineyns.redis.grpc.v1.SetCondition;
import io.github.claudineyns.redis.grpc.v1.SetRequest;
import io.github.claudineyns.redis.grpc.v1.SetResponse;
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

    // ---------- SET ----------

    @Test
    void setStoresValueAndReportsApplied() {
        final String key = "test:set:simple";
        final SetResponse response = doSet(SetRequest.newBuilder()
                .setKey(key).setValue(ByteString.copyFromUtf8("v1")));

        assertTrue(response.getApplied());
        assertEquals("v1", getValue(key));
    }

    @Test
    void setNxOnAbsentApplies() {
        final String key = "test:set:nx-absent";
        final SetResponse response = doSet(SetRequest.newBuilder()
                .setKey(key).setValue(ByteString.copyFromUtf8("v1"))
                .setCondition(SetCondition.SET_CONDITION_NX));

        assertTrue(response.getApplied());
        assertEquals("v1", getValue(key));
    }

    @Test
    void setNxOnExistingDoesNotApply() {
        final String key = "test:set:nx-existing";
        seed(key, "original");

        final SetResponse response = doSet(SetRequest.newBuilder()
                .setKey(key).setValue(ByteString.copyFromUtf8("novo"))
                .setCondition(SetCondition.SET_CONDITION_NX));

        assertFalse(response.getApplied());
        assertEquals("original", getValue(key)); // valor intacto
    }

    @Test
    void setXxOnExistingApplies() {
        final String key = "test:set:xx-existing";
        seed(key, "original");

        final SetResponse response = doSet(SetRequest.newBuilder()
                .setKey(key).setValue(ByteString.copyFromUtf8("novo"))
                .setCondition(SetCondition.SET_CONDITION_XX));

        assertTrue(response.getApplied());
        assertEquals("novo", getValue(key));
    }

    @Test
    void setXxOnAbsentDoesNotApply() {
        final String key = "test:set:xx-absent";
        final SetResponse response = doSet(SetRequest.newBuilder()
                .setKey(key).setValue(ByteString.copyFromUtf8("v1"))
                .setCondition(SetCondition.SET_CONDITION_XX));

        assertFalse(response.getApplied());
        assertNull(getValue(key));
    }

    @Test
    void setWithGetReturnsPrevious() {
        final String key = "test:set:get-prev";
        seed(key, "antigo");

        final SetResponse response = doSet(SetRequest.newBuilder()
                .setKey(key).setValue(ByteString.copyFromUtf8("novo")).setGet(true));

        assertTrue(response.getApplied());
        assertTrue(response.hasPrevious());
        assertEquals("antigo", response.getPrevious().toStringUtf8());
        assertEquals("novo", getValue(key));
    }

    @Test
    void setWithGetOnAbsentHasNoPrevious() {
        final String key = "test:set:get-absent";
        final SetResponse response = doSet(SetRequest.newBuilder()
                .setKey(key).setValue(ByteString.copyFromUtf8("novo")).setGet(true));

        assertTrue(response.getApplied());
        assertFalse(response.hasPrevious());
        assertEquals("novo", getValue(key));
    }

    @Test
    void setWithExpirySetsTtl() {
        final String key = "test:set:ttl";
        final SetResponse response = doSet(SetRequest.newBuilder()
                .setKey(key).setValue(ByteString.copyFromUtf8("v1")).setExSeconds(100));

        assertTrue(response.getApplied());
        final long ttl = redis.send(Request.cmd(Command.TTL).arg(key))
                .await().indefinitely().toLong();
        assertTrue(ttl > 0, "esperava TTL > 0, veio " + ttl);
    }

    @Test
    void setWithGetOnWrongTypeFails() {
        final String key = "test:set:wrongtype";
        redis.send(Request.cmd(Command.HSET).arg(key).arg("f").arg("v")).await().indefinitely();

        final StatusRuntimeException failure = assertThrows(StatusRuntimeException.class, () ->
                doSet(SetRequest.newBuilder()
                        .setKey(key).setValue(ByteString.copyFromUtf8("v1")).setGet(true)));

        assertEquals(Status.Code.FAILED_PRECONDITION, failure.getStatus().getCode());
    }

    // ---------- helpers ----------

    private SetResponse doSet(final SetRequest.Builder builder) {
        return client.set(builder.build()).await().indefinitely();
    }

    private void seed(final String key, final String value) {
        redis.send(Request.cmd(Command.SET).arg(key).arg(value)).await().indefinitely();
    }

    private String getValue(final String key) {
        final GetResponse response = client.get(
                GetRequest.newBuilder().setKey(key).build()).await().indefinitely();
        return response.hasValue() ? response.getValue().toStringUtf8() : null;
    }
}
