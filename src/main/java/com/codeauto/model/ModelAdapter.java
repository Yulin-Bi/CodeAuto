package com.codeauto.model;

import com.codeauto.core.AgentStep;
import com.codeauto.core.ChatMessage;
import java.util.List;

public interface ModelAdapter {
  AgentStep next(List<ChatMessage> messages) throws Exception;
}
