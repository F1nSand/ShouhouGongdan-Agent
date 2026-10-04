package com.aftercare.agent;

public interface ChatProvider {
    AgentPlan plan(String userMessage);
}
