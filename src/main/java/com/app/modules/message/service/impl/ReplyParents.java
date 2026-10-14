package com.app.modules.message.service.impl;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.app.modules.message.entity.Message;
import com.app.modules.message.repository.MessageRepository;

/** Batched lookup of the messages a set of replies point at. */
final class ReplyParents {

    private ReplyParents() {}

    /**
     * Loads, in one query, every parent the given messages reply to.
     *
     * <p>Tombstoned parents are included on purpose: the preview has to say a parent is unavailable
     * rather than silently omit it, and the mapper decides what a tombstone hides.
     *
     * @param repository message repository
     * @param messages the page being rendered
     * @return parents keyed by id; empty when no message in the page is a reply
     */
    static Map<UUID, Message> load(MessageRepository repository, List<Message> messages) {
        Set<UUID> parentIds =
                messages.stream()
                        .map(Message::getReplyToId)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet());
        if (parentIds.isEmpty()) {
            return Map.of();
        }
        Set<UUID> conversationIds =
                messages.stream().map(Message::getConversationId).collect(Collectors.toSet());
        return repository.findReplyParents(parentIds, conversationIds).stream()
                .collect(Collectors.toMap(Message::getId, Function.identity()));
    }
}
