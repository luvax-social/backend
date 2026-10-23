package com.app.modules.social.event;

import java.util.UUID;

/**
 * Published inside the transaction that records a block.
 *
 * <p>Other modules clean up whatever ties the two accounts together, such as people tags and
 * co-authorships, in the same transaction, so a block never commits while a tag between the pair
 * survives it.
 *
 * @param blockerId the account that blocked
 * @param blockedId the account that was blocked
 */
public record UserBlockedEvent(UUID blockerId, UUID blockedId) {}
