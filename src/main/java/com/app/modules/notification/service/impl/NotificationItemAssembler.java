package com.app.modules.notification.service.impl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.app.common.response.UserSummaryResponse;
import com.app.common.response.ViewerRelationshipResponse;
import com.app.modules.admin.dto.response.ModerationNoticeResponse;
import com.app.modules.admin.service.ModerationNoticeService;
import com.app.modules.comment.dto.response.CommentPreviewResponse;
import com.app.modules.comment.service.CommentPreviewService;
import com.app.modules.notification.dto.response.NotificationItemResponse;
import com.app.modules.notification.dto.response.NotificationModerationResponse;
import com.app.modules.notification.dto.response.NotificationPreviewResponse;
import com.app.modules.notification.dto.response.NotificationRelationshipResponse;
import com.app.modules.notification.dto.response.NotificationTargetResponse;
import com.app.modules.notification.entity.enums.NotificationCategory;
import com.app.modules.notification.entity.enums.NotificationType;
import com.app.modules.notification.repository.NotificationFeedRepository;
import com.app.modules.notification.repository.NotificationFeedRepository.FeedRow;
import com.app.modules.notification.repository.NotificationFeedRepository.Key;
import com.app.modules.post.dto.response.PostPreviewResponse;
import com.app.modules.post.service.PostPreviewService;
import com.app.modules.social.service.SocialService;
import com.app.modules.story.dto.response.StoryPreviewResponse;
import com.app.modules.story.service.StoryPreviewService;
import com.app.modules.support.dto.response.SupportTicketPreviewResponse;
import com.app.modules.support.service.SupportTicketPreviewService;
import com.app.modules.users.service.UserSummaryService;

/**
 * Turns feed rows into {@link NotificationItemResponse}s, resolving everything a row references at
 * read time through the module that owns it.
 *
 * <p>The number of queries per page is fixed, not proportional to its size: one for actors, one for
 * hidden-actor counts, one for user summaries, one each for relationships and pending requests, and
 * one batched call per preview kind present on the page (post, comment, story, moderation,
 * support). No other module's repository is used; each preview comes from the owning module's
 * service.
 *
 * <p>Everything is resolved at read time rather than copied into the notification, so a deleted
 * post, an admin-removed comment, an expired story, a post that became private and a block are all
 * reflected the next time the row is read, as an unavailable target with no preview.
 */
@Component
public class NotificationItemAssembler {

    private static final int DISPLAYED_ACTORS = 2;
    private static final NotificationTargetResponse NO_TARGET =
            new NotificationTargetResponse("none", null, null, null, null, null, false);

    private final NotificationFeedRepository feedRepository;
    private final UserSummaryService userSummaryService;
    private final SocialService socialService;
    private final PostPreviewService postPreviewService;
    private final CommentPreviewService commentPreviewService;
    private final StoryPreviewService storyPreviewService;
    private final ModerationNoticeService moderationNoticeService;
    private final SupportTicketPreviewService supportTicketPreviewService;

    public NotificationItemAssembler(
            NotificationFeedRepository feedRepository,
            UserSummaryService userSummaryService,
            SocialService socialService,
            PostPreviewService postPreviewService,
            CommentPreviewService commentPreviewService,
            StoryPreviewService storyPreviewService,
            ModerationNoticeService moderationNoticeService,
            SupportTicketPreviewService supportTicketPreviewService) {
        this.feedRepository = feedRepository;
        this.userSummaryService = userSummaryService;
        this.socialService = socialService;
        this.postPreviewService = postPreviewService;
        this.commentPreviewService = commentPreviewService;
        this.storyPreviewService = storyPreviewService;
        this.moderationNoticeService = moderationNoticeService;
        this.supportTicketPreviewService = supportTicketPreviewService;
    }

    /**
     * Hydrates rows for one viewer, keeping their order.
     *
     * @param newBoundary the position above which a row is new; null when every row is new
     */
    public List<NotificationItemResponse> assemble(
            UUID viewerId, List<FeedRow> rows, Key newBoundary) {
        if (rows.isEmpty()) {
            return List.of();
        }
        List<UUID> rowIds = rows.stream().map(FeedRow::id).toList();
        Map<UUID, List<UUID>> displayActors = feedRepository.findDisplayActors(viewerId, rowIds);
        Map<UUID, Integer> hiddenActors = feedRepository.countHiddenActors(viewerId, rowIds);
        Set<UUID> actorIds = new HashSet<>();
        displayActors.values().forEach(actorIds::addAll);
        Map<UUID, UserSummaryResponse> summaries = userSummaryService.loadSummaries(actorIds);

        List<UUID> followActors = new ArrayList<>();
        Set<UUID> postIds = new HashSet<>();
        Set<UUID> commentIds = new HashSet<>();
        Set<UUID> storyIds = new HashSet<>();
        Set<UUID> ticketIds = new HashSet<>();
        Set<UUID> adminActionIds = new HashSet<>();
        for (FeedRow row : rows) {
            collect(row, displayActors, followActors, postIds, commentIds, storyIds, ticketIds);
            if (row.adminActionId() != null) {
                adminActionIds.add(row.adminActionId());
            }
        }
        Lookups lookups =
                new Lookups(
                        summaries,
                        displayActors,
                        hiddenActors,
                        followActors.isEmpty()
                                ? Map.of()
                                : socialService.loadRelationships(viewerId, followActors),
                        socialService.findPendingRequesters(viewerId, followActors),
                        postIds.isEmpty()
                                ? Map.of()
                                : postPreviewService.loadPreviews(viewerId, postIds),
                        commentIds.isEmpty()
                                ? Map.of()
                                : commentPreviewService.loadPreviews(commentIds),
                        storyIds.isEmpty()
                                ? Map.of()
                                : storyPreviewService.loadPreviews(viewerId, storyIds),
                        adminActionIds.isEmpty()
                                ? Map.of()
                                : moderationNoticeService.loadNotices(viewerId, adminActionIds),
                        ticketIds.isEmpty()
                                ? Map.of()
                                : supportTicketPreviewService.loadPreviews(viewerId, ticketIds));
        return rows.stream().map(row -> toItem(row, lookups, newBoundary)).toList();
    }

    private static void collect(
            FeedRow row,
            Map<UUID, List<UUID>> displayActors,
            List<UUID> followActors,
            Set<UUID> postIds,
            Set<UUID> commentIds,
            Set<UUID> storyIds,
            Set<UUID> ticketIds) {
        switch (row.type()) {
            case LIKE_POST, MENTION_POST, POST_RESTORED -> addIfPresent(postIds, postIdOf(row));
            case LIKE_COMMENT, COMMENT_POST, REPLY_COMMENT, MENTION_COMMENT -> {
                addIfPresent(commentIds, row.entityId());
                addIfPresent(postIds, row.postId());
            }
            case STORY_VIEW -> addIfPresent(storyIds, row.entityId());
            case SUPPORT_TICKET_UPDATE -> addIfPresent(ticketIds, row.entityId());
            case FOLLOW, FOLLOW_REQUEST -> {
                List<UUID> actors = displayActors.get(row.id());
                if (actors != null && !actors.isEmpty()) {
                    followActors.add(actors.get(0));
                }
            }
            default -> {
                // Moderation notices resolve through their audit row, collected by the caller.
            }
        }
    }

    private NotificationItemResponse toItem(FeedRow row, Lookups lookups, Key newBoundary) {
        boolean system = row.category() == NotificationCategory.SYSTEM;
        List<UUID> visibleActorIds =
                system ? List.of() : lookups.displayActors().getOrDefault(row.id(), List.of());
        List<UserSummaryResponse> actors =
                visibleActorIds.stream()
                        .limit(DISPLAYED_ACTORS)
                        .map(lookups.summaries()::get)
                        .filter(Objects::nonNull)
                        .toList();
        int actorCount =
                system
                        ? 0
                        : Math.max(
                                row.actorCount() - lookups.hiddenActors().getOrDefault(row.id(), 0),
                                actors.size());
        Resolved resolved = resolve(row, lookups, visibleActorIds);
        return new NotificationItemResponse(
                row.id(),
                row.type(),
                row.category(),
                actors,
                actorCount,
                row.readAt() != null,
                row.readAt(),
                isNew(row, newBoundary),
                row.activityAt(),
                row.createdAt(),
                resolved.target(),
                resolved.target().available() ? resolved.preview() : null,
                resolved.moderation(),
                resolved.relationship());
    }

    private Resolved resolve(FeedRow row, Lookups lookups, List<UUID> visibleActorIds) {
        return switch (row.type()) {
            case LIKE_POST, MENTION_POST -> postTarget(row, lookups);
            case LIKE_COMMENT, COMMENT_POST, REPLY_COMMENT, MENTION_COMMENT ->
                    commentTarget(row, lookups);
            case STORY_VIEW -> storyTarget(row, lookups);
            case FOLLOW, FOLLOW_REQUEST -> userTarget(visibleActorIds, lookups);
            case SUPPORT_TICKET_UPDATE -> ticketTarget(row, lookups);
            case POST_RESTORED -> {
                Resolved post = postTarget(row, lookups);
                yield new Resolved(post.target(), post.preview(), moderation(row, lookups), null);
            }
            case WARNING,
                            POST_REMOVED,
                            COMMENT_REMOVED,
                            STORY_REMOVED,
                            MESSAGE_REMOVED,
                            REPORT_POST_REMOVED,
                            REPORT_DISMISSED ->
                    new Resolved(
                            new NotificationTargetResponse(
                                    "moderation", null, null, null, null, null, true),
                            null,
                            moderation(row, lookups),
                            null);
            case MESSAGE -> new Resolved(NO_TARGET, null, null, null);
        };
    }

    private static Resolved postTarget(FeedRow row, Lookups lookups) {
        UUID postId = postIdOf(row);
        PostPreviewResponse post = postId == null ? null : lookups.posts().get(postId);
        boolean available = post != null && post.available();
        return new Resolved(
                new NotificationTargetResponse("post", postId, null, null, null, null, available),
                available && post.media() != null
                        ? new NotificationPreviewResponse(post.media(), null, null, null)
                        : null,
                null,
                null);
    }

    private static Resolved commentTarget(FeedRow row, Lookups lookups) {
        CommentPreviewResponse comment = lookups.comments().get(row.entityId());
        UUID postId =
                row.postId() != null ? row.postId() : comment == null ? null : comment.postId();
        PostPreviewResponse post = postId == null ? null : lookups.posts().get(postId);
        boolean available =
                comment != null && comment.available() && post != null && post.available();
        return new Resolved(
                new NotificationTargetResponse(
                        "comment", postId, row.entityId(), null, null, null, available),
                available
                        ? new NotificationPreviewResponse(
                                post.media(),
                                comment.snippet(),
                                row.type() == NotificationType.REPLY_COMMENT
                                        ? comment.parentSnippet()
                                        : null,
                                null)
                        : null,
                null,
                null);
    }

    private static Resolved storyTarget(FeedRow row, Lookups lookups) {
        StoryPreviewResponse story = lookups.stories().get(row.entityId());
        boolean available = story != null && story.available();
        return new Resolved(
                new NotificationTargetResponse(
                        "story", null, null, row.entityId(), null, null, available),
                available
                        ? new NotificationPreviewResponse(
                                story.media(), null, null, story.expiresAt())
                        : null,
                null,
                null);
    }

    private static Resolved userTarget(List<UUID> visibleActorIds, Lookups lookups) {
        if (visibleActorIds.isEmpty()) {
            return new Resolved(
                    new NotificationTargetResponse("user", null, null, null, null, null, false),
                    null,
                    null,
                    null);
        }
        UUID actorId = visibleActorIds.get(0);
        ViewerRelationshipResponse relationship =
                lookups.relationships().getOrDefault(actorId, ViewerRelationshipResponse.NONE);
        return new Resolved(
                new NotificationTargetResponse("user", null, null, null, null, actorId, true),
                null,
                null,
                new NotificationRelationshipResponse(
                        relationship.isFollowing(),
                        relationship.isFollowRequested(),
                        lookups.pendingRequesters().contains(actorId)));
    }

    private static Resolved ticketTarget(FeedRow row, Lookups lookups) {
        SupportTicketPreviewResponse ticket = lookups.tickets().get(row.entityId());
        boolean available = ticket != null && ticket.available();
        return new Resolved(
                new NotificationTargetResponse(
                        "support_ticket", null, null, null, row.entityId(), null, available),
                null,
                null,
                null);
    }

    private static NotificationModerationResponse moderation(FeedRow row, Lookups lookups) {
        ModerationNoticeResponse notice =
                row.adminActionId() == null ? null : lookups.notices().get(row.adminActionId());
        if (notice == null) {
            // A notice whose audit row could not be linked (rows from before the link existed)
            // still shows its recorded reason, and simply offers no appeal.
            return new NotificationModerationResponse(
                    null, row.message(), null, null, null, null, false);
        }
        return new NotificationModerationResponse(
                notice.actionType(),
                notice.reason() != null ? notice.reason() : row.message(),
                notice.affectedKind(),
                notice.affectedSnippet(),
                notice.affectedAt(),
                notice.appealable() ? notice.adminActionId() : null,
                notice.appealable());
    }

    private static boolean isNew(FeedRow row, Key boundary) {
        if (boundary == null) {
            return true;
        }
        int byTime = row.activityAt().compareTo(boundary.activityAt());
        return byTime > 0 || (byTime == 0 && compareAsPostgres(row.id(), boundary.id()) > 0);
    }

    // PostgreSQL orders uuid values bytewise, which is an unsigned comparison of the two halves;
    // UUID.compareTo compares them signed, so on a tie in activity_at it could disagree with the
    // tuple comparison the SQL side of the watermark uses.
    static int compareAsPostgres(UUID left, UUID right) {
        int high =
                Long.compareUnsigned(left.getMostSignificantBits(), right.getMostSignificantBits());
        return high != 0
                ? high
                : Long.compareUnsigned(
                        left.getLeastSignificantBits(), right.getLeastSignificantBits());
    }

    private static UUID postIdOf(FeedRow row) {
        return row.postId() != null ? row.postId() : row.entityId();
    }

    private static void addIfPresent(Collection<UUID> ids, UUID id) {
        if (id != null) {
            ids.add(id);
        }
    }

    private record Resolved(
            NotificationTargetResponse target,
            NotificationPreviewResponse preview,
            NotificationModerationResponse moderation,
            NotificationRelationshipResponse relationship) {}

    private record Lookups(
            Map<UUID, UserSummaryResponse> summaries,
            Map<UUID, List<UUID>> displayActors,
            Map<UUID, Integer> hiddenActors,
            Map<UUID, ViewerRelationshipResponse> relationships,
            Set<UUID> pendingRequesters,
            Map<UUID, PostPreviewResponse> posts,
            Map<UUID, CommentPreviewResponse> comments,
            Map<UUID, StoryPreviewResponse> stories,
            Map<UUID, ModerationNoticeResponse> notices,
            Map<UUID, SupportTicketPreviewResponse> tickets) {}
}
