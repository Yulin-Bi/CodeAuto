package com.codeauto.permissions;

public record PermissionResponse(PermissionDecision decision, String feedback) {
  public PermissionResponse(PermissionDecision decision) {
    this(decision, null);
  }
}
