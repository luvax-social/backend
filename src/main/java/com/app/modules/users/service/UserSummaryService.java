package com.app.modules.users.service;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

import com.app.common.response.UserSummaryResponse;

/**
 * Resolves user ids to their shared public summaries in a single batched query.
 *
 * <p>The single point every module uses to embed an author or actor, so the batch strategy and the
 * deleted-user placeholder policy live in exactly one place instead of being reimplemented per
 * module.
 */
public interface UserSummaryService {

    /**
     * Loads a public summary for every requested id.
     *
     * <p>The returned map contains an entry for every distinct requested id: a live user resolves
     * to its real summary, while a soft-deleted or unknown id resolves to a placeholder whose
     * {@code username} is null and whose {@code displayName} is a fixed marker. Duplicate ids are
     * deduplicated into a single query and a single map entry.
     *
     * @param ids user ids to resolve; may contain duplicates
     * @return a map from each distinct id to its summary or placeholder; empty when {@code ids} is
     *     empty
     */
    Map<UUID, UserSummaryResponse> loadSummaries(Collection<UUID> ids);

    /**
     * Tells whether an account row exists, soft-deleted or not.
     *
     * <p>For a writer whose store cannot enforce the reference itself: ClickHouse has no foreign
     * keys, so the check the {@code user_events} foreign key used to make is made here instead. A
     * soft-deleted account still exists, exactly as it did for that constraint.
     *
     * @param userId account to look for
     * @return true when a row with this identifier exists
     */
    boolean exists(UUID userId);
}
