package com.codeauto.permissions;

public interface PermissionPrompt {
  PermissionDecision ask(PermissionRequest request);

  default PermissionResponse askDetailed(PermissionRequest request) {
    return new PermissionResponse(ask(request));
  }
}
