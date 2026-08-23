package com.codeauto.web;

import com.codeauto.permissions.PermissionDecision;
import com.codeauto.permissions.PermissionRequest;
import com.codeauto.permissions.PermissionResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebPermissionBrokerTest {
  @Test
  void exposesAndResolvesAWebApproval() throws Exception {
    var broker = new WebPermissionBroker();
    var request = editRequest();
    CompletableFuture<PermissionResponse> response = CompletableFuture.supplyAsync(
        () -> broker.promptFor("session-a").askDetailed(request));

    WebPermissionBroker.PendingApproval pending = awaitPending(broker);
    assertEquals("session-a", pending.sessionId());
    assertEquals(request, pending.request());

    broker.resolve(pending.id(), PermissionDecision.ALLOW_ONCE, "  okay  ",
        WebPermissionBroker.AutoPolicy.ASK);
    assertEquals(PermissionDecision.ALLOW_ONCE, response.get(Duration.ofSeconds(2).toMillis(),
        java.util.concurrent.TimeUnit.MILLISECONDS).decision());
    assertTrue(broker.pending().isEmpty());
  }

  @Test
  void editAutoPolicyOnlyLastsForTheCurrentTurn() throws Exception {
    var broker = new WebPermissionBroker();
    CompletableFuture<PermissionResponse> first = CompletableFuture.supplyAsync(
        () -> broker.promptFor("session-b").askDetailed(editRequest()));
    WebPermissionBroker.PendingApproval pending = awaitPending(broker);
    broker.resolve(pending.id(), PermissionDecision.ALLOW_ONCE, null,
        WebPermissionBroker.AutoPolicy.EDITS);
    first.get(Duration.ofSeconds(2).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);

    PermissionResponse automatic = broker.promptFor("session-b").askDetailed(editRequest());
    assertEquals(PermissionDecision.ALLOW_ALL_TURN, automatic.decision());
    assertTrue(broker.pending().isEmpty());

    broker.endTurn("session-b");
    CompletableFuture<PermissionResponse> nextTurn = CompletableFuture.supplyAsync(
        () -> broker.promptFor("session-b").askDetailed(editRequest()));
    assertEquals("session-b", awaitPending(broker).sessionId());
    broker.endTurn("session-b");
    assertEquals(PermissionDecision.DENY_ONCE,
        nextTurn.get(Duration.ofSeconds(2).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS).decision());
  }

  @Test
  void rejectsUnsupportedDecisions() throws Exception {
    var broker = new WebPermissionBroker();
    CompletableFuture<PermissionResponse> response = CompletableFuture.supplyAsync(
        () -> broker.promptFor("session-c").askDetailed(editRequest()));
    WebPermissionBroker.PendingApproval pending = awaitPending(broker);

    assertThrows(IllegalArgumentException.class, () -> broker.resolve(pending.id(),
        PermissionDecision.ALLOW_TURN, null, WebPermissionBroker.AutoPolicy.ASK));
    assertFalse(response.isDone());
    broker.endTurn("session-c");
  }

  @Test
  void denyingDoesNotEnableTheSelectedAutoPolicy() throws Exception {
    var broker = new WebPermissionBroker();
    CompletableFuture<PermissionResponse> first = CompletableFuture.supplyAsync(
        () -> broker.promptFor("session-d").askDetailed(editRequest()));
    WebPermissionBroker.PendingApproval pending = awaitPending(broker);
    broker.resolve(pending.id(), PermissionDecision.DENY_ONCE, null,
        WebPermissionBroker.AutoPolicy.ALL);
    assertEquals(PermissionDecision.DENY_ONCE,
        first.get(Duration.ofSeconds(2).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS).decision());

    CompletableFuture<PermissionResponse> second = CompletableFuture.supplyAsync(
        () -> broker.promptFor("session-d").askDetailed(editRequest()));
    assertEquals("session-d", awaitPending(broker).sessionId());
    broker.endTurn("session-d");
    second.get(Duration.ofSeconds(2).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
  }

  private static PermissionRequest editRequest() {
    return new PermissionRequest("edit", "修改测试文件", "test-demo.txt",
        List.of(PermissionDecision.ALLOW_ONCE, PermissionDecision.ALLOW_ALWAYS,
            PermissionDecision.ALLOW_ALL_TURN, PermissionDecision.DENY_ONCE,
            PermissionDecision.DENY_WITH_FEEDBACK));
  }

  private static WebPermissionBroker.PendingApproval awaitPending(WebPermissionBroker broker)
      throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
    while (broker.pending().isEmpty() && System.nanoTime() < deadline) Thread.sleep(5);
    assertFalse(broker.pending().isEmpty(), "审批请求应在超时前出现");
    return broker.pending().get(0);
  }
}
