package com.codeauto.background;

public record BackgroundTask(
    String id,
    String appId,
    String command,
    String workdir,
    long pid,
    long startedAt,
    String status,
    String healthUrl,
    int healthPort,
    int startupTimeoutSeconds,
    String healthStatus,
    String output
) {
}
