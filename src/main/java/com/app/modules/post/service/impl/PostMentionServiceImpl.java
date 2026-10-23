package com.app.modules.post.service.impl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.common.outbox.service.OutboxService;
import com.app.common.response.UserSummaryResponse;
import com.app.common.text.MentionParser;
import com.app.modules.post.dto.response.PostMentionResponse;
import com.app.modules.post.entity.Post;
import com.app.modules.post.entity.PostMention;
import com.app.modules.post.entity.PostMentionId;
import com.app.modules.post.messaging.PostEventTypes;
import com.app.modules.post.repository.PostMentionRepository;
import com.app.modules.post.repository.PostUserRepository;
import com.app.modules.post.service.PostMentionService;
import com.app.modules.social.policy.InteractionDecision;
import com.app.modules.social.policy.InteractionOutcome;
import com.app.modules.social.policy.InteractionPolicyService;
import com.app.modules.social.policy.InteractionRequest;
import com.app.modules.social.policy.InteractionType;
import com.app.modules.social.service.SocialService;
import com.app.modules.users.entity.User;
import com.app.modules.users.service.UserSummaryService;

@Service
public class PostMentionServiceImpl implements PostMentionService {

    static final int MAX_MENTIONS = 10;

    private final PostMentionRepository postMentionRepository;
    private final PostUserRepository postUserRepository;
    private final InteractionPolicyService interactionPolicyService;
    private final UserSummaryService userSummaryService;
    private final SocialService socialService;
    private final OutboxService outboxService;

    public PostMentionServiceImpl(
            PostMentionRepository postMentionRepository,
            PostUserRepository postUserRepository,
            InteractionPolicyService interactionPolicyService,
            UserSummaryService userSummaryService,
            SocialService socialService,
            OutboxService outboxService) {
        this.postMentionRepository = postMentionRepository;
        this.postUserRepository = postUserRepository;
        this.interactionPolicyService = interactionPolicyService;
        this.userSummaryService = userSummaryService;
        this.socialService = socialService;
        this.outboxService = outboxService;
    }

    @Override
    @Transactional
    public MentionChange replaceCaptionMentions(Post post) {
        UUID postId = post.getId();
        UUID authorId = post.getUserId();
        Map<UUID, PostMention> existing = new HashMap<>();
        for (PostMention row : postMentionRepository.findByPostId(postId)) {
            existing.put(row.getMentionedUserId(), row);
        }
        // Keyed by the mentioned account so two handles that differ only in case, or two handles
        // that resolve to one account, store one row.
        Map<UUID, String> handleByUser = new LinkedHashMap<>();
        Set<UUID> notifiable = new HashSet<>();
        for (String handle : MentionParser.handles(post.getCaption(), MAX_MENTIONS)) {
            User mentioned = postUserRepository.findActiveByUsername(handle).orElse(null);
            if (mentioned == null || handleByUser.containsKey(mentioned.getId())) {
                continue;
            }
            InteractionDecision decision =
                    interactionPolicyService.evaluate(
                            new InteractionRequest(
                                    authorId,
                                    mentioned.getId(),
                                    InteractionType.MENTION,
                                    post.getCaption(),
                                    List.of(),
                                    null));
            if (decision.outcome() == InteractionOutcome.DENY) {
                continue;
            }
            handleByUser.put(mentioned.getId(), handle);
            if (decision.outcome() == InteractionOutcome.ALLOW
                    && !mentioned.getId().equals(authorId)) {
                notifiable.add(mentioned.getId());
            }
        }
        List<UUID> added = new ArrayList<>();
        for (Map.Entry<UUID, String> entry : handleByUser.entrySet()) {
            PostMention row = existing.remove(entry.getKey());
            if (row == null) {
                postMentionRepository.save(
                        PostMention.builder()
                                .id(new PostMentionId(postId, entry.getKey()))
                                .handle(entry.getValue())
                                .build());
                added.add(entry.getKey());
            } else if (!entry.getValue().equals(row.getHandle())) {
                row.setHandle(entry.getValue());
            }
        }
        postMentionRepository.deleteAll(existing.values());
        return new MentionChange(
                List.copyOf(handleByUser.keySet()),
                handleByUser.keySet().stream().filter(notifiable::contains).toList(),
                List.copyOf(added));
    }

    @Override
    @Transactional
    public void announce(Post post, Collection<UUID> userIds, Collection<UUID> notifiable) {
        for (UUID userId : userIds) {
            Map<String, Object> data = new HashMap<>();
            data.put("postId", post.getId().toString());
            data.put("mentionedUserId", userId.toString());
            data.put("notify", notifiable.contains(userId));
            outboxService.enqueue(
                    PostEventTypes.POST_MENTION_CREATED_V1,
                    PostEventTypes.POST_MENTION_CREATED_V1,
                    "post",
                    post.getId(),
                    post.getUserId(),
                    data);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, List<PostMentionResponse>> loadForPosts(
            UUID viewerId, Collection<UUID> postIds) {
        if (postIds.isEmpty()) {
            return Map.of();
        }
        List<PostMention> rows = postMentionRepository.findByPostIdIn(postIds);
        if (rows.isEmpty()) {
            return Map.of();
        }
        Set<UUID> blocked = socialService.findBlockedEitherDirection(viewerId);
        Map<UUID, UserSummaryResponse> users =
                userSummaryService.loadSummaries(
                        rows.stream().map(PostMention::getMentionedUserId).distinct().toList());
        Map<UUID, List<PostMentionResponse>> result = new HashMap<>();
        for (PostMention row : rows) {
            UserSummaryResponse user = users.get(row.getMentionedUserId());
            // A deleted account resolves to a placeholder with no username; it is not a mention
            // anyone can follow, so the text stays plain.
            if (user == null
                    || user.username() == null
                    || blocked.contains(row.getMentionedUserId())) {
                continue;
            }
            result.computeIfAbsent(row.getPostId(), id -> new ArrayList<>())
                    .add(
                            new PostMentionResponse(
                                    row.getHandle(), row.getMentionedUserId(), user.username()));
        }
        return result;
    }
}
