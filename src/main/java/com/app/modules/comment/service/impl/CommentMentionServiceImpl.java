package com.app.modules.comment.service.impl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.common.response.UserSummaryResponse;
import com.app.common.text.MentionParser;
import com.app.modules.comment.entity.CommentMention;
import com.app.modules.comment.entity.CommentMentionId;
import com.app.modules.comment.repository.CommentMentionRepository;
import com.app.modules.comment.repository.CommentUserRepository;
import com.app.modules.comment.service.CommentMentionService;
import com.app.modules.post.dto.response.PostMentionResponse;
import com.app.modules.social.policy.InteractionDecision;
import com.app.modules.social.policy.InteractionOutcome;
import com.app.modules.social.policy.InteractionPolicyService;
import com.app.modules.social.policy.InteractionRequest;
import com.app.modules.social.policy.InteractionType;
import com.app.modules.social.repository.BlockRepository;
import com.app.modules.users.entity.User;
import com.app.modules.users.service.UserSummaryService;

@Service
public class CommentMentionServiceImpl implements CommentMentionService {

    private static final int MAX_MENTIONS = 10;

    private final CommentMentionRepository commentMentionRepository;
    private final CommentUserRepository commentUserRepository;
    private final InteractionPolicyService interactionPolicy;
    private final UserSummaryService userSummaryService;
    private final BlockRepository blockRepository;

    public CommentMentionServiceImpl(
            CommentMentionRepository commentMentionRepository,
            CommentUserRepository commentUserRepository,
            InteractionPolicyService interactionPolicy,
            UserSummaryService userSummaryService,
            BlockRepository blockRepository) {
        this.commentMentionRepository = commentMentionRepository;
        this.commentUserRepository = commentUserRepository;
        this.interactionPolicy = interactionPolicy;
        this.userSummaryService = userSummaryService;
        this.blockRepository = blockRepository;
    }

    // A mention the policy denies, by a block in either direction, resolves to nothing: the
    // mentioned user must not receive a notification (the harassment channel the stealth block
    // model exists to close), and the mentioner must not be able to tell that resolution was
    // suppressed rather than the username simply not existing. A mention hidden by the mentioned
    // account's interaction limit is stored, so the text renders as a link, but never notified.
    @Override
    @Transactional
    public List<String> replaceMentions(UUID commentId, UUID actorId, String content) {
        // Keyed by the mentioned account, so two spellings of one name store one row.
        Map<UUID, String> handleByUser = new LinkedHashMap<>();
        List<String> notifiable = new ArrayList<>();
        for (String handle : MentionParser.handles(content, MAX_MENTIONS)) {
            User mentioned =
                    commentUserRepository.findByUsernameAndDeletedAtIsNull(handle).orElse(null);
            if (mentioned == null || handleByUser.containsKey(mentioned.getId())) {
                continue;
            }
            InteractionDecision decision =
                    interactionPolicy.evaluate(
                            new InteractionRequest(
                                    actorId,
                                    mentioned.getId(),
                                    InteractionType.MENTION,
                                    null,
                                    null,
                                    null));
            if (decision.outcome() == InteractionOutcome.DENY) {
                continue;
            }
            handleByUser.put(mentioned.getId(), handle);
            if (decision.outcome() == InteractionOutcome.ALLOW
                    && !mentioned.getId().equals(actorId)) {
                notifiable.add(mentioned.getId().toString());
            }
        }
        commentMentionRepository.deleteByCommentId(commentId);
        commentMentionRepository.flush();
        for (Map.Entry<UUID, String> entry : handleByUser.entrySet()) {
            commentMentionRepository.save(
                    CommentMention.builder()
                            .id(new CommentMentionId(commentId, entry.getKey()))
                            .handle(entry.getValue())
                            .build());
        }
        return notifiable;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, List<PostMentionResponse>> loadForComments(
            UUID viewerId, Collection<UUID> commentIds) {
        if (commentIds.isEmpty()) {
            return Map.of();
        }
        List<CommentMention> rows = commentMentionRepository.findByCommentIdIn(commentIds);
        if (rows.isEmpty()) {
            return Map.of();
        }
        Set<UUID> blocked =
                viewerId == null
                        ? Set.of()
                        : Set.copyOf(blockRepository.findBlockedCounterpartyIds(viewerId));
        Map<UUID, UserSummaryResponse> users =
                userSummaryService.loadSummaries(
                        rows.stream().map(CommentMention::getMentionedUserId).distinct().toList());
        Map<UUID, List<PostMentionResponse>> result = new HashMap<>();
        for (CommentMention row : rows) {
            UserSummaryResponse user = users.get(row.getMentionedUserId());
            // A deleted account resolves to a placeholder with no username; it is not a mention
            // anyone can follow, so the text stays plain.
            if (user == null
                    || user.username() == null
                    || blocked.contains(row.getMentionedUserId())) {
                continue;
            }
            result.computeIfAbsent(row.getCommentId(), id -> new ArrayList<>())
                    .add(
                            new PostMentionResponse(
                                    row.getHandle(), row.getMentionedUserId(), user.username()));
        }
        return result;
    }
}
