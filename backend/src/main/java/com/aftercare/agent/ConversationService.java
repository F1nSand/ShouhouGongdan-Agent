package com.aftercare.agent;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.aftercare.common.ApiException;
import com.aftercare.identity.ActorContext;
import com.aftercare.identity.Role;
import java.time.Clock;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConversationService {
    private static final Set<Role> INTERNAL_ROLES = Set.of(
            Role.CUSTOMER_SERVICE, Role.SERVICE_SUPERVISOR, Role.WAREHOUSE_OPERATOR, Role.ADMIN);

    private final AgentRunRepository repository;
    private final Clock clock;

    public ConversationService(AgentRunRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional
    public ConversationView create(ActorContext actor, String requestedTitle) {
        if (!INTERNAL_ROLES.contains(actor.role())) {
            throw new ApiException("FORBIDDEN", "当前账号不能创建会话", HttpStatus.FORBIDDEN);
        }
        String title = requestedTitle == null || requestedTitle.isBlank() ? "新售后会话" : requestedTitle.trim();
        if (title.length() > 120) {
            throw new ApiException("INVALID_ARGUMENT", "会话标题不能超过 120 个字符", HttpStatus.BAD_REQUEST);
        }
        UUID id = UUID.randomUUID();
        repository.insertConversation(actor.tenantId(), actor.actorId(), id, title);
        return new ConversationView(id, title, "ACTIVE", clock.instant());
    }

    public Page<AgentRunRepository.ConversationSummary> list(ActorContext actor, int page, int size) {
        validatePage(page, size);
        return new Page<>(repository.conversations(actor.tenantId(), actor.actorId(), size, (page - 1) * size),
                page, size);
    }

    public List<AgentRunRepository.MessageView> messages(ActorContext actor, UUID conversationId, int limit) {
        if (limit < 1 || limit > 200) {
            throw new ApiException("INVALID_ARGUMENT", "消息数量必须在 1 到 200 之间", HttpStatus.BAD_REQUEST);
        }
        if (!repository.conversationOwned(actor.tenantId(), actor.actorId(), conversationId)) {
            throw new ApiException("RESOURCE_NOT_FOUND", "未找到该会话", HttpStatus.NOT_FOUND);
        }
        return repository.messages(actor.tenantId(), actor.actorId(), conversationId, limit);
    }

    private static void validatePage(int page, int size) {
        if (page < 1 || size < 1 || size > 100) {
            throw new ApiException("INVALID_ARGUMENT", "分页参数无效", HttpStatus.BAD_REQUEST);
        }
    }

    public record ConversationView(UUID conversationId, String title, String status, Instant createdAt) {
    }

    public record Page<T>(List<T> items, int page, int size) {
        public Page {
            items = List.copyOf(items);
        }
    }
}
