package com.codeauto.todo;

import java.time.Instant;

public record TodoEntry(
    String id,
    String content,
    String status,
    String activeForm,
    Instant createdAt,
    Instant updatedAt) {
}
