package com.app.modules.social.dto.response;

import java.util.List;
import java.util.UUID;

/**
 * An account's pending follow requests, summarised: a count up to a limit and the newest
 * requesters.
 *
 * @param count pending requests, counted up to the limit the caller asked for plus one
 * @param recentRequesterIds the newest requesters, newest first
 */
public record PendingFollowRequestSummary(long count, List<UUID> recentRequesterIds) {}
