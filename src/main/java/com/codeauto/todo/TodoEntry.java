package com.codeauto.todo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TodoEntry(
    String id,
    String content,
    String status,
    String activeForm,
    String groupId,
    String groupTitle,
    Instant createdAt,
    Instant updatedAt) {

  public TodoEntry(
      String id,
      String content,
      String status,
      String activeForm,
      Instant createdAt,
      Instant updatedAt) {
    this(id, content, status, activeForm, null, null, createdAt, updatedAt);
  }
}
