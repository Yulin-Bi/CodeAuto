package com.codeauto.web;

import com.codeauto.permissions.PermissionDecision;
import com.codeauto.permissions.PermissionPrompt;
import com.codeauto.permissions.PermissionRequest;
import com.codeauto.permissions.PermissionResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Bridges blocking tool permission checks to the Web UI without weakening PermissionManager. */
final class WebPermissionBroker {
  enum AutoPolicy { ASK, EDITS, ALL }

  record PendingApproval(String id, String sessionId, PermissionRequest request, Instant createdAt) {}

  private record Pending(PendingApproval approval, CompletableFuture<PermissionResponse> future) {}

  private final Map<String, Pending> pending = new ConcurrentHashMap<>();
  private final Map<String, AutoPolicy> policies = new ConcurrentHashMap<>();
  private volatile Consumer<PendingApproval> listener = approval -> {};

  void onRequest(Consumer<PendingApproval> callback) {
    listener = callback == null ? approval -> {} : callback;
  }

  PermissionPrompt promptFor(String sessionId) {
    return new PermissionPrompt() {
      @Override public PermissionDecision ask(PermissionRequest request) {
        return askDetailed(request).decision();
      }

      @Override public PermissionResponse askDetailed(PermissionRequest request) {
        PermissionResponse automatic = automaticDecision(sessionId, request);
        if (automatic != null) return automatic;
        String id = UUID.randomUUID().toString();
        PendingApproval approval = new PendingApproval(id, sessionId, request, Instant.now());
        CompletableFuture<PermissionResponse> future = new CompletableFuture<>();
        pending.put(id, new Pending(approval, future));
        listener.accept(approval);
        try {
          return future.join();
        } finally {
          pending.remove(id);
        }
      }
    };
  }

  List<PendingApproval> pending() {
    return pending.values().stream().map(Pending::approval)
        .sorted(java.util.Comparator.comparing(PendingApproval::createdAt)).toList();
  }

  Optional<PendingApproval> pendingForSession(String sessionId) {
    return pending().stream().filter(item -> item.sessionId().equals(sessionId)).findFirst();
  }

  PendingApproval resolve(String id, PermissionDecision decision, String feedback, AutoPolicy policy) {
    Pending item = pending.get(id);
    if (item == null) throw new IllegalArgumentException("审批请求已结束或不存在");
    validate(item.approval().request(), decision);
    if (policy != null && policy != AutoPolicy.ASK && isAllowed(decision)) {
      policies.put(item.approval().sessionId(), policy);
    }
    String normalizedFeedback = feedback == null || feedback.isBlank() ? null : feedback.strip();
    item.future().complete(new PermissionResponse(decision, normalizedFeedback));
    return item.approval();
  }

  void endTurn(String sessionId) {
    policies.remove(sessionId);
    pending.entrySet().removeIf(entry -> {
      if (!entry.getValue().approval().sessionId().equals(sessionId)) return false;
      entry.getValue().future().complete(new PermissionResponse(PermissionDecision.DENY_ONCE));
      return true;
    });
  }

  void close() {
    for (Pending item : pending.values()) {
      item.future().complete(new PermissionResponse(PermissionDecision.DENY_ONCE));
    }
    pending.clear();
    policies.clear();
  }

  private PermissionResponse automaticDecision(String sessionId, PermissionRequest request) {
    AutoPolicy policy = policies.getOrDefault(sessionId, AutoPolicy.ASK);
    if (policy == AutoPolicy.ALL) {
      return new PermissionResponse("edit".equals(request.kind())
          ? PermissionDecision.ALLOW_ALL_TURN : PermissionDecision.ALLOW_TURN);
    }
    if (policy == AutoPolicy.EDITS && "edit".equals(request.kind())) {
      return new PermissionResponse(PermissionDecision.ALLOW_ALL_TURN);
    }
    return null;
  }

  private static void validate(PermissionRequest request, PermissionDecision decision) {
    if (decision == null) throw new IllegalArgumentException("审批决定不能为空");
    if (decision == PermissionDecision.ALLOW_ALL_TURN && "edit".equals(request.kind())) return;
    if (!request.choices().contains(decision)) {
      throw new IllegalArgumentException("当前请求不支持该审批决定：" + decision);
    }
  }

  private static boolean isAllowed(PermissionDecision decision) {
    return decision == PermissionDecision.ALLOW_ONCE
        || decision == PermissionDecision.ALLOW_ALWAYS
        || decision == PermissionDecision.ALLOW_TURN
        || decision == PermissionDecision.ALLOW_ALL_TURN;
  }
}
