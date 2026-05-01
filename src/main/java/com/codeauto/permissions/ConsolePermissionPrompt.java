package com.codeauto.permissions;

import java.io.Console;

public class ConsolePermissionPrompt implements PermissionPrompt {
  @Override
  public PermissionDecision ask(PermissionRequest request) {
    return askDetailed(request).decision();
  }

  @Override
  public PermissionResponse askDetailed(PermissionRequest request) {
    Console console = System.console();
    if (console == null) {
      return new PermissionResponse(
          "edit".equals(request.kind()) ? PermissionDecision.ALLOW_ONCE : PermissionDecision.DENY_ONCE);
    }
    console.printf("%s%nScope: %s%n", request.summary(), request.scope());
    console.printf("[o] allow once, [a] allow always, [t] allow this turn, [f] reject with feedback, [d] deny: ");
    String answer = console.readLine();
    return switch (answer == null ? "" : answer.trim().toLowerCase()) {
      case "o", "once" -> new PermissionResponse(PermissionDecision.ALLOW_ONCE);
      case "a", "always" -> new PermissionResponse(PermissionDecision.ALLOW_ALWAYS);
      case "t", "turn" -> new PermissionResponse(PermissionDecision.ALLOW_TURN);
      case "f", "feedback" -> {
        String feedback = console.readLine("Feedback for the model: ");
        yield new PermissionResponse(PermissionDecision.DENY_WITH_FEEDBACK, feedback);
      }
      default -> new PermissionResponse(PermissionDecision.DENY_ONCE);
    };
  }
}
