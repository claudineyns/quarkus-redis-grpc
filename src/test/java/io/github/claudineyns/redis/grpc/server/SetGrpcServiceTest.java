package io.github.claudineyns.redis.grpc.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.claudineyns.redis.grpc.v1.SAddRequest;
import io.github.claudineyns.redis.grpc.v1.SCardRequest;
import io.github.claudineyns.redis.grpc.v1.SIsMemberRequest;
import io.github.claudineyns.redis.grpc.v1.SMIsMemberRequest;
import io.github.claudineyns.redis.grpc.v1.SMembersRequest;
import io.github.claudineyns.redis.grpc.v1.SPopRequest;
import io.github.claudineyns.redis.grpc.v1.SRemRequest;
import io.github.claudineyns.redis.grpc.v1.SetMembers;
import io.github.claudineyns.redis.grpc.v1.SetMemberships;
import io.github.claudineyns.redis.grpc.v1.SetService;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.quarkus.grpc.GrpcClient;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.mutiny.redis.client.Command;
import io.vertx.mutiny.redis.client.Redis;
import io.vertx.mutiny.redis.client.Request;
import jakarta.inject.Inject;

@QuarkusTest
class SetGrpcServiceTest {

    @GrpcClient("sets")
    SetService client;

    @Inject
    Redis redis;

    @Test
    void sAddReturnsAddedCountAndDedups() {
        del("test:set:add");
        final long added = client.sAdd(SAddRequest.newBuilder().setKey("test:set:add")
                .addMembers("a").addMembers("b").addMembers("a") // "a" duplicado
                .build()).await().indefinitely().getCount();
        assertEquals(2L, added);
        // readd de membro existente → 0 adicionados
        final long readd = client.sAdd(SAddRequest.newBuilder().setKey("test:set:add")
                .addMembers("a").build()).await().indefinitely().getCount();
        assertEquals(0L, readd);
    }

    @Test
    void sRemReturnsRemovedCount() {
        del("test:set:rem");
        client.sAdd(SAddRequest.newBuilder().setKey("test:set:rem")
                .addMembers("a").addMembers("b").build()).await().indefinitely();
        final long removed = client.sRem(SRemRequest.newBuilder().setKey("test:set:rem")
                .addMembers("a").addMembers("z").build()).await().indefinitely().getCount();
        assertEquals(1L, removed);
    }

    @Test
    void sCardReturnsCardinality() {
        del("test:set:card");
        client.sAdd(SAddRequest.newBuilder().setKey("test:set:card")
                .addMembers("a").addMembers("b").addMembers("c").build()).await().indefinitely();
        assertEquals(3L, client.sCard(SCardRequest.newBuilder().setKey("test:set:card").build())
                .await().indefinitely().getCount());
    }

    @Test
    void sCardAbsentReturnsZero() {
        del("test:set:card-absent");
        assertEquals(0L, client.sCard(SCardRequest.newBuilder().setKey("test:set:card-absent").build())
                .await().indefinitely().getCount());
    }

    @Test
    void sIsMemberTrueAndFalse() {
        del("test:set:ism");
        client.sAdd(SAddRequest.newBuilder().setKey("test:set:ism")
                .addMembers("a").build()).await().indefinitely();
        assertTrue(client.sIsMember(SIsMemberRequest.newBuilder()
                .setKey("test:set:ism").setMember("a").build()).await().indefinitely().getIsMember());
        assertFalse(client.sIsMember(SIsMemberRequest.newBuilder()
                .setKey("test:set:ism").setMember("z").build()).await().indefinitely().getIsMember());
    }

    @Test
    void sMIsMemberAlignedToInput() {
        del("test:set:mism");
        client.sAdd(SAddRequest.newBuilder().setKey("test:set:mism")
                .addMembers("a").addMembers("c").build()).await().indefinitely();
        final SetMemberships response = client.sMIsMember(SMIsMemberRequest.newBuilder()
                .setKey("test:set:mism").addMembers("a").addMembers("b").addMembers("c")
                .build()).await().indefinitely();
        assertEquals(List.of(true, false, true), response.getMembersList());
    }

    @Test
    void sMembersReturnsAll() {
        del("test:set:mem");
        client.sAdd(SAddRequest.newBuilder().setKey("test:set:mem")
                .addMembers("a").addMembers("b").build()).await().indefinitely();
        final SetMembers response = client.sMembers(SMembersRequest.newBuilder()
                .setKey("test:set:mem").build()).await().indefinitely();
        assertEquals(2, response.getMembersCount());
        assertTrue(response.getMembersList().contains("a"));
        assertTrue(response.getMembersList().contains("b"));
    }

    @Test
    void sAddEmptyRejected() {
        final StatusRuntimeException failure = assertThrows(StatusRuntimeException.class, () ->
                client.sAdd(SAddRequest.newBuilder().setKey("test:set:empty").build())
                        .await().indefinitely());
        assertEquals(Status.Code.INVALID_ARGUMENT, failure.getStatus().getCode());
    }

    @Test
    void sAddOnWrongTypeFailsPrecondition() {
        del("test:set:wrong");
        redis.send(Request.cmd(Command.SET).arg("test:set:wrong").arg("v")).await().indefinitely();
        final StatusRuntimeException failure = assertThrows(StatusRuntimeException.class, () ->
                client.sAdd(SAddRequest.newBuilder().setKey("test:set:wrong").addMembers("a").build())
                        .await().indefinitely());
        assertEquals(Status.Code.FAILED_PRECONDITION, failure.getStatus().getCode());
    }

    // ---------- pop (Fatia 2) ----------

    @Test
    void sPopWithoutCountReturnsOneAndRemoves() {
        del("test:set:pop1");
        client.sAdd(SAddRequest.newBuilder().setKey("test:set:pop1")
                .addMembers("a").build()).await().indefinitely();
        final SetMembers popped = client.sPop(SPopRequest.newBuilder()
                .setKey("test:set:pop1").build()).await().indefinitely();
        assertEquals(1, popped.getMembersCount());
        assertEquals("a", popped.getMembers(0));
        assertEquals(0L, rawCard("test:set:pop1"));
    }

    @Test
    void sPopWithCountReturnsUpToCount() {
        del("test:set:pop2");
        client.sAdd(SAddRequest.newBuilder().setKey("test:set:pop2")
                .addMembers("a").addMembers("b").addMembers("c").build()).await().indefinitely();
        final SetMembers popped = client.sPop(SPopRequest.newBuilder()
                .setKey("test:set:pop2").setCount(2).build()).await().indefinitely();
        assertEquals(2, popped.getMembersCount());
        assertEquals(1L, rawCard("test:set:pop2"));
    }

    @Test
    void sPopCountExceedingCardinalityReturnsAll() {
        del("test:set:pop3");
        client.sAdd(SAddRequest.newBuilder().setKey("test:set:pop3")
                .addMembers("a").addMembers("b").build()).await().indefinitely();
        final SetMembers popped = client.sPop(SPopRequest.newBuilder()
                .setKey("test:set:pop3").setCount(5).build()).await().indefinitely();
        assertEquals(2, popped.getMembersCount());
        assertEquals(0L, rawCard("test:set:pop3"));
    }

    @Test
    void sPopAbsentReturnsEmpty() {
        del("test:set:pop-absent");
        final SetMembers noCount = client.sPop(SPopRequest.newBuilder()
                .setKey("test:set:pop-absent").build()).await().indefinitely();
        assertEquals(0, noCount.getMembersCount());
        final SetMembers withCount = client.sPop(SPopRequest.newBuilder()
                .setKey("test:set:pop-absent").setCount(3).build()).await().indefinitely();
        assertEquals(0, withCount.getMembersCount());
    }

    // ---------- helpers ----------

    private long rawCard(final String key) {
        return redis.send(Request.cmd(Command.SCARD).arg(key)).await().indefinitely().toLong();
    }

    private void del(final String key) {
        redis.send(Request.cmd(Command.DEL).arg(key)).await().indefinitely();
    }
}
