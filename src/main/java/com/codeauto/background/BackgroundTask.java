package com.codeauto.background;

public record BackgroundTask(
    String id,
    String command,
    long pid,
    long startedAt,
    String status,
    String output
) {
}
