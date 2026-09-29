package com.app.modules.users.service.impl;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.common.response.UserSummaryResponse;
import com.app.modules.users.repository.UserRepository;
import com.app.modules.users.service.UserSummaryService;

@Service
public class UserSummaryServiceImpl implements UserSummaryService {

    /** Display name of the placeholder returned for a soft-deleted or unknown user. */
    public static final String DELETED_DISPLAY_NAME = "Deleted user";

    private final UserRepository userRepository;

    public UserSummaryServiceImpl(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, UserSummaryResponse> loadSummaries(Collection<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        Set<UUID> distinct = new LinkedHashSet<>(ids);
        Map<UUID, UserSummaryResponse> resolved =
                userRepository.findSummariesByIdIn(distinct).stream()
                        .collect(Collectors.toMap(UserSummaryResponse::id, s -> s));
        Map<UUID, UserSummaryResponse> result = new LinkedHashMap<>(distinct.size());
        for (UUID id : distinct) {
            result.put(id, resolved.getOrDefault(id, placeholder(id)));
        }
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean exists(UUID userId) {
        return userId != null && userRepository.existsIncludingSoftDeleted(userId);
    }

    private static UserSummaryResponse placeholder(UUID id) {
        return new UserSummaryResponse(id, null, DELETED_DISPLAY_NAME, null, false);
    }
}
