package io.github.claudineyns.redis.grpc.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.google.protobuf.ByteString;

import io.github.claudineyns.redis.grpc.v1.AppendRequest;
import io.github.claudineyns.redis.grpc.v1.CounterValue;
import io.github.claudineyns.redis.grpc.v1.DecrByRequest;
import io.github.claudineyns.redis.grpc.v1.DecrRequest;
import io.github.claudineyns.redis.grpc.v1.GetDelRequest;
import io.github.claudineyns.redis.grpc.v1.GetExRequest;
import io.github.claudineyns.redis.grpc.v1.GetRequest;
import io.github.claudineyns.redis.grpc.v1.GetResponse;
import io.github.claudineyns.redis.grpc.v1.IncrByRequest;
import io.github.claudineyns.redis.grpc.v1.IncrRequest;
import io.github.claudineyns.redis.grpc.v1.KeyValue;
import io.github.claudineyns.redis.grpc.v1.LengthValue;
import io.github.claudineyns.redis.grpc.v1.MGetRequest;
import io.github.claudineyns.redis.grpc.v1.MGetResponse;
import io.github.claudineyns.redis.grpc.v1.MSetRequest;
import io.github.claudineyns.redis.grpc.v1.SetCondition;
import io.github.claudineyns.redis.grpc.v1.StrlenRequest;
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

    // ---------- MSET / MGET ----------

    @Test
    void mSetAndMGetRoundTrip() {
        client.mSet(MSetRequest.newBuilder()
                .addEntries(kv("test:m:a", "1"))
                .addEntries(kv("test:m:b", "2"))
                .addEntries(kv("test:m:c", "3"))
                .build()).await().indefinitely();

        final MGetResponse response = client.mGet(MGetRequest.newBuilder()
                .addKeys("test:m:a").addKeys("test:m:b").addKeys("test:m:c").build())
                .await().indefinitely();

        assertEquals(3, response.getValuesCount());
        assertEquals("1", response.getValues(0).getValue().toStringUtf8());
        assertEquals("2", response.getValues(1).getValue().toStringUtf8());
        assertEquals("3", response.getValues(2).getValue().toStringUtf8());
    }

    @Test
    void mGetReturnsNilForMissing() {
        seed("test:m:present", "v");

        final MGetResponse response = client.mGet(MGetRequest.newBuilder()
                .addKeys("test:m:present").addKeys("test:m:missing").build())
                .await().indefinitely();

        assertTrue(response.getValues(0).hasValue());
        assertEquals("v", response.getValues(0).getValue().toStringUtf8());
        assertFalse(response.getValues(1).hasValue()); // nil
    }

    @Test
    void mGetPreservesOrder() {
        seed("test:m:o1", "first");
        seed("test:m:o2", "second");

        final MGetResponse response = client.mGet(MGetRequest.newBuilder()
                .addKeys("test:m:o2").addKeys("test:m:o1").build())
                .await().indefinitely();

        assertEquals("second", response.getValues(0).getValue().toStringUtf8());
        assertEquals("first", response.getValues(1).getValue().toStringUtf8());
    }

    @Test
    void mGetWrongTypeReturnsNil() {
        final String key = "test:m:hash";
        redis.send(Request.cmd(Command.HSET).arg(key).arg("f").arg("v")).await().indefinitely();

        final MGetResponse response = client.mGet(MGetRequest.newBuilder()
                .addKeys(key).build()).await().indefinitely();

        assertEquals(1, response.getValuesCount());
        assertFalse(response.getValues(0).hasValue()); // nil, NÃO erro
    }

    @Test
    void mSetEmptyRejected() {
        final StatusRuntimeException failure = assertThrows(StatusRuntimeException.class, () ->
                client.mSet(MSetRequest.newBuilder().build()).await().indefinitely());
        assertEquals(Status.Code.INVALID_ARGUMENT, failure.getStatus().getCode());
    }

    @Test
    void mGetEmptyRejected() {
        final StatusRuntimeException failure = assertThrows(StatusRuntimeException.class, () ->
                client.mGet(MGetRequest.newBuilder().build()).await().indefinitely());
        assertEquals(Status.Code.INVALID_ARGUMENT, failure.getStatus().getCode());
    }

    // ---------- INCR / INCRBY ----------

    @Test
    void incrOnAbsentReturnsOne() {
        final CounterValue response = client.incr(
                IncrRequest.newBuilder().setKey("test:c:new").build()).await().indefinitely();
        assertEquals(1L, response.getValue());
    }

    @Test
    void incrIncrementsExisting() {
        seed("test:c:five", "5");
        final CounterValue response = client.incr(
                IncrRequest.newBuilder().setKey("test:c:five").build()).await().indefinitely();
        assertEquals(6L, response.getValue());
    }

    @Test
    void incrByPositive() {
        seed("test:c:by", "10");
        final CounterValue response = client.incrBy(IncrByRequest.newBuilder()
                .setKey("test:c:by").setIncrement(5).build()).await().indefinitely();
        assertEquals(15L, response.getValue());
    }

    @Test
    void incrByNegative() {
        seed("test:c:byn", "10");
        final CounterValue response = client.incrBy(IncrByRequest.newBuilder()
                .setKey("test:c:byn").setIncrement(-3).build()).await().indefinitely();
        assertEquals(7L, response.getValue());
    }

    @Test
    void incrByOnAbsentStartsFromZero() {
        final CounterValue response = client.incrBy(IncrByRequest.newBuilder()
                .setKey("test:c:byabsent").setIncrement(7).build()).await().indefinitely();
        assertEquals(7L, response.getValue());
    }

    @Test
    void incrOnNonIntegerFails() {
        seed("test:c:text", "abc");
        final StatusRuntimeException failure = assertThrows(StatusRuntimeException.class, () ->
                client.incr(IncrRequest.newBuilder().setKey("test:c:text").build())
                        .await().indefinitely());
        assertEquals(Status.Code.FAILED_PRECONDITION, failure.getStatus().getCode());
    }

    // ---------- DECR / DECRBY ----------

    @Test
    void decrOnAbsentReturnsMinusOne() {
        final CounterValue response = client.decr(
                DecrRequest.newBuilder().setKey("test:d:new").build()).await().indefinitely();
        assertEquals(-1L, response.getValue());
    }

    @Test
    void decrDecrementsExisting() {
        seed("test:d:five", "5");
        final CounterValue response = client.decr(
                DecrRequest.newBuilder().setKey("test:d:five").build()).await().indefinitely();
        assertEquals(4L, response.getValue());
    }

    @Test
    void decrByPositive() {
        seed("test:d:by", "10");
        final CounterValue response = client.decrBy(DecrByRequest.newBuilder()
                .setKey("test:d:by").setDecrement(4).build()).await().indefinitely();
        assertEquals(6L, response.getValue());
    }

    @Test
    void decrByNegative() {
        seed("test:d:byn", "10");
        final CounterValue response = client.decrBy(DecrByRequest.newBuilder()
                .setKey("test:d:byn").setDecrement(-5).build()).await().indefinitely();
        assertEquals(15L, response.getValue());
    }

    @Test
    void decrOnNonIntegerFails() {
        seed("test:d:text", "abc");
        final StatusRuntimeException failure = assertThrows(StatusRuntimeException.class, () ->
                client.decr(DecrRequest.newBuilder().setKey("test:d:text").build())
                        .await().indefinitely());
        assertEquals(Status.Code.FAILED_PRECONDITION, failure.getStatus().getCode());
    }

    // ---------- GETEX ----------

    @Test
    void getExNoOptionReturnsValueKeepingTtl() {
        final String key = "test:getex:keep";
        seedWithTtl(key, "v", 100);

        final GetResponse response = client.getEx(
                GetExRequest.newBuilder().setKey(key).build()).await().indefinitely();

        assertTrue(response.hasValue());
        assertEquals("v", response.getValue().toStringUtf8());
        assertTrue(ttl(key) > 0, "TTL deveria ter sido mantido");
    }

    @Test
    void getExWithExSetsTtl() {
        final String key = "test:getex:setttl";
        seed(key, "v"); // sem TTL

        final GetResponse response = client.getEx(GetExRequest.newBuilder()
                .setKey(key).setExSeconds(100).build()).await().indefinitely();

        assertEquals("v", response.getValue().toStringUtf8());
        assertTrue(ttl(key) > 0, "TTL deveria ter sido definido");
    }

    @Test
    void getExPersistRemovesTtl() {
        final String key = "test:getex:persist";
        seedWithTtl(key, "v", 100);

        final GetResponse response = client.getEx(GetExRequest.newBuilder()
                .setKey(key).setPersist(true).build()).await().indefinitely();

        assertEquals("v", response.getValue().toStringUtf8());
        assertEquals(-1L, ttl(key)); // -1 = sem TTL (persistente)
    }

    @Test
    void getExOnAbsentReturnsNoValue() {
        final GetResponse response = client.getEx(
                GetExRequest.newBuilder().setKey("test:getex:absent").build())
                .await().indefinitely();
        assertFalse(response.hasValue());
    }

    @Test
    void getExOnWrongTypeFails() {
        final String key = "test:getex:hash";
        redis.send(Request.cmd(Command.HSET).arg(key).arg("f").arg("v")).await().indefinitely();

        final StatusRuntimeException failure = assertThrows(StatusRuntimeException.class, () ->
                client.getEx(GetExRequest.newBuilder().setKey(key).build()).await().indefinitely());
        assertEquals(Status.Code.FAILED_PRECONDITION, failure.getStatus().getCode());
    }

    // ---------- GETDEL ----------

    @Test
    void getDelReturnsValueAndDeletes() {
        final String key = "test:getdel:present";
        seed(key, "v");

        final GetResponse response = client.getDel(
                GetDelRequest.newBuilder().setKey(key).build()).await().indefinitely();

        assertTrue(response.hasValue());
        assertEquals("v", response.getValue().toStringUtf8());
        assertNull(getValue(key)); // a chave foi apagada
    }

    @Test
    void getDelOnAbsentReturnsNoValue() {
        final GetResponse response = client.getDel(
                GetDelRequest.newBuilder().setKey("test:getdel:absent").build())
                .await().indefinitely();
        assertFalse(response.hasValue());
    }

    @Test
    void getDelOnWrongTypeFails() {
        final String key = "test:getdel:hash";
        redis.send(Request.cmd(Command.HSET).arg(key).arg("f").arg("v")).await().indefinitely();

        final StatusRuntimeException failure = assertThrows(StatusRuntimeException.class, () ->
                client.getDel(GetDelRequest.newBuilder().setKey(key).build()).await().indefinitely());
        assertEquals(Status.Code.FAILED_PRECONDITION, failure.getStatus().getCode());
    }

    // ---------- APPEND / STRLEN ----------

    @Test
    void appendCreatesWhenAbsent() {
        final String key = "test:append:new";
        final LengthValue response = client.append(AppendRequest.newBuilder()
                .setKey(key).setValue(ByteString.copyFromUtf8("Hello")).build())
                .await().indefinitely();

        assertEquals(5L, response.getLength());
        assertEquals("Hello", getValue(key));
    }

    @Test
    void appendConcatenates() {
        final String key = "test:append:concat";
        seed(key, "Hello");

        final LengthValue response = client.append(AppendRequest.newBuilder()
                .setKey(key).setValue(ByteString.copyFromUtf8(" World")).build())
                .await().indefinitely();

        assertEquals(11L, response.getLength());
        assertEquals("Hello World", getValue(key));
    }

    @Test
    void appendOnWrongTypeFails() {
        final String key = "test:append:hash";
        redis.send(Request.cmd(Command.HSET).arg(key).arg("f").arg("v")).await().indefinitely();

        final StatusRuntimeException failure = assertThrows(StatusRuntimeException.class, () ->
                client.append(AppendRequest.newBuilder()
                        .setKey(key).setValue(ByteString.copyFromUtf8("x")).build())
                        .await().indefinitely());
        assertEquals(Status.Code.FAILED_PRECONDITION, failure.getStatus().getCode());
    }

    @Test
    void strlenReturnsLength() {
        seed("test:strlen:k", "Hello World");
        final LengthValue response = client.strlen(
                StrlenRequest.newBuilder().setKey("test:strlen:k").build()).await().indefinitely();
        assertEquals(11L, response.getLength());
    }

    @Test
    void strlenOnAbsentReturnsZero() {
        final LengthValue response = client.strlen(
                StrlenRequest.newBuilder().setKey("test:strlen:absent").build()).await().indefinitely();
        assertEquals(0L, response.getLength());
    }

    @Test
    void strlenOnWrongTypeFails() {
        final String key = "test:strlen:hash";
        redis.send(Request.cmd(Command.HSET).arg(key).arg("f").arg("v")).await().indefinitely();

        final StatusRuntimeException failure = assertThrows(StatusRuntimeException.class, () ->
                client.strlen(StrlenRequest.newBuilder().setKey(key).build()).await().indefinitely());
        assertEquals(Status.Code.FAILED_PRECONDITION, failure.getStatus().getCode());
    }

    // ---------- helpers ----------

    private static KeyValue kv(final String key, final String value) {
        return KeyValue.newBuilder().setKey(key).setValue(ByteString.copyFromUtf8(value)).build();
    }

    private SetResponse doSet(final SetRequest.Builder builder) {
        return client.set(builder.build()).await().indefinitely();
    }

    private void seed(final String key, final String value) {
        redis.send(Request.cmd(Command.SET).arg(key).arg(value)).await().indefinitely();
    }

    private void seedWithTtl(final String key, final String value, final long seconds) {
        redis.send(Request.cmd(Command.SET).arg(key).arg(value).arg("EX").arg(Long.toString(seconds)))
                .await().indefinitely();
    }

    private long ttl(final String key) {
        return redis.send(Request.cmd(Command.TTL).arg(key)).await().indefinitely().toLong();
    }

    private String getValue(final String key) {
        final GetResponse response = client.get(
                GetRequest.newBuilder().setKey(key).build()).await().indefinitely();
        return response.hasValue() ? response.getValue().toStringUtf8() : null;
    }
}
