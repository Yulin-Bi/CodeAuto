package com.codeauto.permissions;

import java.util.List;

public record PermissionRequest(String kind, String summary, String scope, List<PermissionDecision> choices) {
}
