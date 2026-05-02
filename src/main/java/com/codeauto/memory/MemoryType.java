package com.codeauto.memory;

public enum MemoryType {
  USER,
  FEEDBACK,
  PROJECT,
  REFERENCE;

  public static MemoryType from(String value) {
    if (value == null || value.isBlank()) return PROJECT;
    for (MemoryType type : values()) {
      if (type.name().equalsIgnoreCase(value.trim())) return type;
    }
    return PROJECT;
  }
}
