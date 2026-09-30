package com.app.modules.hashtag.service.impl;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import jakarta.persistence.EntityManager;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.common.enums.ApiErrorCode;
import com.app.common.exception.AppException;
import com.app.modules.hashtag.dto.response.HashtagSummaryResponse;
import com.app.modules.hashtag.entity.Hashtag;
import com.app.modules.hashtag.entity.PostHashtag;
import com.app.modules.hashtag.entity.PostHashtagId;
import com.app.modules.hashtag.enums.HashtagStatus;
import com.app.modules.hashtag.repository.HashtagIndexProjection;
import com.app.modules.hashtag.repository.HashtagRepository;
import com.app.modules.hashtag.repository.PostHashtagNameProjection;
import com.app.modules.hashtag.repository.PostHashtagRepository;
import com.app.modules.hashtag.service.HashtagIndexEventPublisher;
import com.app.modules.hashtag.service.HashtagService;

@Service
public class HashtagServiceImpl implements HashtagService {

    /** A deleted hashtag is not listed on a post; a banned one still is. */
    private static final List<HashtagStatus> VISIBLE_ON_POST =
            List.of(HashtagStatus.ACTIVE, HashtagStatus.BANNED);

    // hashtags.name is VARCHAR(100); names beyond this bound would fail the insert at the DB.
    private static final int MAX_NAME_LENGTH = 100;

    private final HashtagRepository hashtagRepository;
    private final PostHashtagRepository postHashtagRepository;
    private final EntityManager entityManager;
    private final HashtagIndexEventPublisher indexEventPublisher;

    public HashtagServiceImpl(
            HashtagRepository hashtagRepository,
            PostHashtagRepository postHashtagRepository,
            EntityManager entityManager,
            HashtagIndexEventPublisher indexEventPublisher) {
        this.hashtagRepository = hashtagRepository;
        this.postHashtagRepository = postHashtagRepository;
        this.entityManager = entityManager;
        this.indexEventPublisher = indexEventPublisher;
    }

    @Override
    public String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.strip();
        String withoutHash = trimmed.replaceFirst("^#+", "");
        // Locale.ROOT lowercasing keeps the unique-index key stable across server locales.
        String normalized = withoutHash.strip().toLowerCase(Locale.ROOT);
        // codePointCount, not length(), matches the VARCHAR(100) character limit: a
        // supplementary-plane letter (e.g. CJK Extension B) occupies two UTF-16 code units in a
        // Java String but counts as one Postgres character, so length() would drop valid values
        // early. Over-long tags are dropped rather than truncated so a truncated prefix never
        // aliases a legitimately distinct shorter tag.
        int codePointCount = normalized.codePointCount(0, normalized.length());
        return codePointCount > MAX_NAME_LENGTH ? "" : normalized;
    }

    @Override
    @Transactional
    public void upsertHashtagsForPost(UUID postId, List<String> rawTags) {
        LinkedHashSet<String> names = normalizedSet(rawTags);
        List<String> banned = bannedAmong(names);
        if (!banned.isEmpty()) {
            throw new AppException(ApiErrorCode.POST_BANNED_HASHTAG, Map.of("bannedTags", banned));
        }
        associate(postId, names);
    }

    @Override
    @Transactional
    public List<String> upsertHashtagsForPostSkippingBanned(UUID postId, List<String> rawTags) {
        LinkedHashSet<String> names = normalizedSet(rawTags);
        List<String> banned = bannedAmong(names);
        names.removeAll(banned);
        associate(postId, names);
        return banned;
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> findBannedNames(Collection<String> rawNames) {
        return bannedAmong(normalizedSet(rawNames));
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, List<HashtagSummaryResponse>> getVisibleHashtagsForPosts(
            Collection<UUID> postIds) {
        if (postIds.isEmpty()) {
            return Map.of();
        }
        return postHashtagRepository.findNamedByPostIdIn(postIds, VISIBLE_ON_POST).stream()
                .collect(
                        Collectors.groupingBy(
                                PostHashtagNameProjection::getPostId,
                                LinkedHashMap::new,
                                Collectors.mapping(
                                        p ->
                                                new HashtagSummaryResponse(
                                                        p.getHashtagId(), p.getName()),
                                        Collectors.toList())));
    }

    @Override
    @Transactional
    public void removeHashtagsForPost(UUID postId) {
        List<UUID> affected = postHashtagRepository.findHashtagIdsByPostId(postId);
        postHashtagRepository.deleteAllByPostId(postId);

        // Flush so the decremented post_count is readable by the projection query below.
        entityManager.flush();
        Map<UUID, HashtagIndexProjection> projections =
                hashtagRepository.findIndexProjectionsByIdIn(affected).stream()
                        .collect(
                                Collectors.toMap(
                                        HashtagIndexProjection::getId, Function.identity()));

        for (UUID id : affected) {
            HashtagIndexProjection projection = projections.get(id);
            if (projection != null && projection.getPostCount() > 0) {
                indexEventPublisher.enqueueSync(id);
            } else {
                indexEventPublisher.enqueueDelete(id);
            }
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, List<UUID>> getHashtagIdsForPosts(Collection<UUID> postIds) {
        if (postIds.isEmpty()) {
            return Map.of();
        }
        return postHashtagRepository.findAllByIdPostIdIn(postIds).stream()
                .collect(
                        Collectors.groupingBy(
                                ph -> ph.getId().getPostId(),
                                Collectors.mapping(
                                        ph -> ph.getId().getHashtagId(), Collectors.toList())));
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> getHashtagNamesForPost(UUID postId) {
        return hashtagRepository.findNamesByPostId(postId);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, List<String>> getHashtagNamesForPosts(Collection<UUID> postIds) {
        if (postIds.isEmpty()) {
            return Map.of();
        }
        return hashtagRepository.findNamesByPostIds(postIds).stream()
                .collect(
                        Collectors.groupingBy(
                                PostHashtagNameProjection::getPostId,
                                LinkedHashMap::new,
                                Collectors.mapping(
                                        PostHashtagNameProjection::getName, Collectors.toList())));
    }

    private LinkedHashSet<String> normalizedSet(Collection<String> rawTags) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (String raw : rawTags) {
            String n = normalize(raw);
            if (!n.isBlank()) {
                names.add(n);
            }
        }
        return names;
    }

    // Guards the empty case here rather than in the query: "IN ()" is a SQL syntax error, and a
    // caption with no tags is the common case, not an edge one.
    private List<String> bannedAmong(Collection<String> normalizedNames) {
        if (normalizedNames.isEmpty()) {
            return List.of();
        }
        // A List rather than the caller's set: the parameter binds into an IN list, and a stable
        // ordered argument is what makes the emitted statement reproducible.
        List<String> probe = List.copyOf(normalizedNames);
        Set<String> banned = new HashSet<>(hashtagRepository.findBannedNames(probe));
        return probe.stream().filter(banned::contains).toList();
    }

    private void associate(UUID postId, Collection<String> names) {
        LinkedHashSet<UUID> affectedIds = new LinkedHashSet<>();
        for (String name : names) {
            hashtagRepository.upsertByName(name);
            Hashtag hashtag = hashtagRepository.findByName(name).orElseThrow();
            postHashtagRepository.save(
                    PostHashtag.builder().id(new PostHashtagId(postId, hashtag.getId())).build());
            affectedIds.add(hashtag.getId());
        }

        for (UUID id : affectedIds) {
            indexEventPublisher.enqueueSync(id);
        }
    }
}
