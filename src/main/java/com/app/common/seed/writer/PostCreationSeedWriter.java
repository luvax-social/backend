package com.app.common.seed.writer;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.app.common.seed.loader.SeedContent;
import com.app.common.seed.model.PlaceSeed;
import com.app.common.seed.model.UserSeed;
import com.app.common.seed.outbox.SeedOutboxBatchWriter.UserEventImport;
import com.app.common.seed.time.SeedTimeline;
import com.app.modules.recommendation.enums.UserEventType;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Seeds what the post creation features add to the dataset: the places registry and the posts that
 * carry a place, alt text, people tags, co-authors and stored mentions, the tag preferences of
 * accounts, scheduled posts, and the behavioural signals those rows stand for.
 *
 * <p>Everything here is derived from rows earlier writers already produced, with one seeded random
 * stream, so a run is repeatable and no tag, invitation or mention points at an account or a post
 * that does not exist. Rows are written the way the database would hold them after the production
 * flow ran: a tag honours the tagged account's permission and approval setting, an invitation never
 * crosses a block, a mention never crosses one either. The policy itself is not called, because the
 * seed replays history rather than a request.
 *
 * <p>Each row records its behavioural signal in the {@link InteractionSignalLedger}, which {@link
 * AnalyticsSeedWriter} imports at the end of the run, so a signal exists only for something that
 * happened. {@code NotificationSeedWriter} reads the same tables back to write the notifications
 * the flow would have produced.
 *
 * <p>Place moderation (the ban, restore and deletion audit rows and the place reports) belongs to
 * {@link PlaceModerationSeedWriter}, which runs after the moderation writer because it needs
 * administrators and reports in place.
 */
@Slf4j
@Service
@Profile("seed & (dev | prod)")
@RequiredArgsConstructor
public class PostCreationSeedWriter {

    private static final long RANDOM_SEED = 8_830_417L;

    /** Drafts that were once scheduled and failed to publish; the notifications cite these. */
    public static final int FAILED_SCHEDULE_DRAFTS = 5;

    /** The reasons those failures carry, in the order the drafts are taken. */
    public static final List<String> FAILED_SCHEDULE_REASONS =
            List.of(
                    "banned_hashtag",
                    "place_unavailable",
                    "media_unavailable",
                    "banned_hashtag",
                    "place_unavailable");

    /** The drafts of {@link #FAILED_SCHEDULE_DRAFTS}, oldest identifier first. */
    public static final String FAILED_SCHEDULE_DRAFTS_SQL =
            "SELECT id, user_id, updated_at FROM posts WHERE status = 'draft' AND deleted_at IS NULL"
                    + " ORDER BY id LIMIT "
                    + FAILED_SCHEDULE_DRAFTS;

    private static final int SCHEDULED_POSTS = 10;
    // One is due within minutes so the publisher can be watched; the rest stay scheduled.
    private static final int[] SCHEDULED_AHEAD_MINUTES = {
        4, 90, 240, 1_440, 2_880, 4_320, 7_200, 10_080, 14_400, 21_600
    };
    private static final List<String> SCHEDULED_CAPTIONS =
            List.of(
                    "Something new is coming tomorrow, stay tuned",
                    "Sunday morning ritual, scheduled so I can sleep in",
                    "Announcing the next project soon",
                    "A little behind-the-scenes from this week",
                    "Scheduling this ahead of the trip",
                    "Throwback from last summer, posting it on the anniversary",
                    "Mid-week mood board",
                    "Saving this one for the weekend",
                    "Launch day post, set to go live at noon",
                    "End of month recap");

    private static final int TAG_EVERY_NTH_POST = 4;
    private static final int MENTION_EVERY_NTH_POST = 9;
    private static final int MENTION_EVERY_NTH_COMMENT = 11;
    private static final int COLLABORATION_EVERY_NTH_POST = 14;
    private static final int TAGS_REMOVED_AFTER_THE_FACT = 8;
    private static final int UNARCHIVE_SIGNALS = 6;

    // The collaboration states, in the order they are dealt out. Every state appears at least five
    // times, which is what the enum coverage assertion asks of the seed.
    private static final String[] COLLABORATION_STATES = {
        "invited",
        "invited",
        "invited",
        "invited",
        "invited",
        "invited",
        "invited",
        "invited",
        "accepted",
        "accepted",
        "accepted",
        "accepted",
        "accepted",
        "accepted",
        "accepted",
        "accepted",
        "accepted",
        "accepted",
        "accepted",
        "accepted",
        "accepted",
        "accepted",
        "accepted",
        "accepted",
        "declined",
        "declined",
        "declined",
        "declined",
        "declined",
        "declined",
        "left",
        "left",
        "left",
        "left",
        "left",
        "left",
        "removed",
        "removed",
        "removed",
        "removed",
        "removed",
        "removed"
    };

    private final JdbcTemplate jdbc;
    private final InteractionSignalLedger signalLedger;

    /**
     * Writes every post creation row and records its signals.
     *
     * @param content the loaded seed content, whose places registry is written here
     * @param usersByUsername username-to-id map produced by {@link UserSeedWriter#write}
     * @param timeline the shared timeline; only its reference instant is used
     */
    public void write(
            SeedContent content, Map<String, UUID> usersByUsername, SeedTimeline timeline) {
        Random random = new Random(RANDOM_SEED);
        Instant now = timeline.referenceNow();
        Context context = loadContext(content, usersByUsername);

        Map<String, UUID> placeIdByName = writePlaces(content.places(), context, random, now);
        int placed = assignPlaces(placeIdByName, content.places(), context, random);
        int altTexts = writeAltTexts(context);
        applyTagPreferences(content, usersByUsername);
        List<TagRow> tags = writeTags(context, random, now);
        List<CollaborationRow> collaborations = writeCollaborations(context, random, now);
        List<MentionRow> mentions = writeMentions(context, random);
        int scheduled = writeScheduledPosts(context, random, now);

        recordSignals(context, tags, collaborations, mentions, now, random);
        log.info(
                "[seed] post creation: {} places, {} posts placed, {} alt texts, {} tags, {}"
                        + " collaborations, {} mentions, {} scheduled posts",
                placeIdByName.size(),
                placed,
                altTexts,
                tags.size(),
                collaborations.size(),
                mentions.size(),
                scheduled);
    }

    // ---------------------------------------------------------------- context

    private record PostRow(
            UUID id, UUID ownerId, String caption, String type, Instant publishedAt) {}

    private record MediaRow(UUID id, UUID postId, int position, String mediaType) {}

    private record Context(
            Map<UUID, String> usernameById,
            List<UUID> activeUserIds,
            Set<UUID> qaUserIds,
            Set<String> acceptedFollows,
            Set<String> blockedPairs,
            List<PostRow> publishedPosts,
            Map<UUID, List<MediaRow>> mediaByPost,
            UUID adminId) {

        boolean follows(UUID follower, UUID following) {
            return acceptedFollows.contains(follower + ">" + following);
        }

        boolean blocked(UUID a, UUID b) {
            return blockedPairs.contains(pair(a, b));
        }
    }

    private static String pair(UUID a, UUID b) {
        return a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a;
    }

    private Context loadContext(SeedContent content, Map<String, UUID> usersByUsername) {
        Map<UUID, String> usernameById = new HashMap<>();
        usersByUsername.forEach((username, id) -> usernameById.put(id, username));
        Set<UUID> qaUserIds = new HashSet<>();
        for (UserSeed user : content.users()) {
            if (user.isQaAccount()) {
                qaUserIds.add(usersByUsername.get(user.username()));
            }
        }
        List<UUID> activeUserIds =
                jdbc.queryForList(
                        "SELECT id FROM users WHERE status = 'active' AND deleted_at IS NULL"
                                + " ORDER BY id",
                        UUID.class);
        Set<String> follows = new HashSet<>();
        jdbc.query(
                "SELECT follower_id, following_id FROM follows WHERE status = 'accepted'",
                rs -> {
                    follows.add(rs.getObject("follower_id") + ">" + rs.getObject("following_id"));
                });
        Set<String> blocked = new HashSet<>();
        jdbc.query(
                "SELECT blocker_id, blocked_id FROM blocks",
                rs -> {
                    blocked.add(pair((UUID) rs.getObject(1), (UUID) rs.getObject(2)));
                });
        List<PostRow> published = new ArrayList<>();
        jdbc.query(
                "SELECT id, user_id, caption, post_type::text AS post_type, published_at FROM posts"
                        + " WHERE status = 'published' AND deleted_at IS NULL ORDER BY id",
                rs -> {
                    published.add(
                            new PostRow(
                                    (UUID) rs.getObject("id"),
                                    (UUID) rs.getObject("user_id"),
                                    rs.getString("caption"),
                                    rs.getString("post_type"),
                                    rs.getTimestamp("published_at").toInstant()));
                });
        Map<UUID, List<MediaRow>> mediaByPost = new LinkedHashMap<>();
        jdbc.query(
                "SELECT pm.id, pm.post_id, pm.position, ma.media_type::text AS media_type FROM"
                        + " post_media pm JOIN media_assets ma ON ma.id = pm.media_asset_id ORDER BY"
                        + " pm.post_id, pm.position",
                rs -> {
                    MediaRow row =
                            new MediaRow(
                                    (UUID) rs.getObject("id"),
                                    (UUID) rs.getObject("post_id"),
                                    rs.getInt("position"),
                                    rs.getString("media_type"));
                    mediaByPost.computeIfAbsent(row.postId(), id -> new ArrayList<>()).add(row);
                });
        List<UUID> admins =
                jdbc.queryForList(
                        "SELECT id FROM users WHERE role = 'admin' AND status = 'active' ORDER BY"
                                + " id LIMIT 1",
                        UUID.class);
        return new Context(
                usernameById,
                activeUserIds,
                qaUserIds,
                follows,
                blocked,
                published,
                mediaByPost,
                admins.isEmpty() ? null : admins.get(0));
    }

    // ---------------------------------------------------------------- places

    private int assignPlaces(
            Map<String, UUID> placeIdByName,
            List<PlaceSeed> places,
            Context context,
            Random random) {
        List<UUID> plain = idsOf(places, placeIdByName, "active", "none");
        List<UUID> restored = idsOf(places, placeIdByName, "active", "unbanned");
        List<UUID> removed = new ArrayList<>(idsOf(places, placeIdByName, "banned", "none"));
        removed.addAll(idsOf(places, placeIdByName, "deleted", "none"));
        List<Object[]> updates = new ArrayList<>();
        int index = 0;
        for (PostRow post : context.publishedPosts()) {
            if (index++ % 4 != 0) {
                continue;
            }
            double roll = random.nextDouble();
            UUID placeId;
            if (roll < 0.70) {
                placeId = plain.get(random.nextInt(plain.size()));
            } else if (roll < 0.90) {
                placeId = restored.get(random.nextInt(restored.size()));
            } else {
                // An administrator removed the place after the post used it.
                placeId = removed.get(random.nextInt(removed.size()));
            }
            updates.add(new Object[] {placeId, post.id()});
        }
        // A few drafts carry a place too, so the composer has something to restore.
        List<UUID> draftIds =
                jdbc.queryForList(
                        "SELECT id FROM posts WHERE status = 'draft' AND deleted_at IS NULL"
                                + " ORDER BY id LIMIT 3",
                        UUID.class);
        for (UUID draftId : draftIds) {
            updates.add(new Object[] {plain.get(random.nextInt(plain.size())), draftId});
        }
        jdbc.batchUpdate("UPDATE posts SET place_id = ? WHERE id = ?", updates);
        return updates.size();
    }

    private static List<UUID> idsOf(
            List<PlaceSeed> places, Map<String, UUID> idByName, String status, String history) {
        return places.stream()
                .filter(place -> status.equals(place.status()) && history.equals(place.history()))
                .map(place -> idByName.get(place.name()))
                .toList();
    }

    private Map<String, UUID> writePlaces(
            List<PlaceSeed> places, Context context, Random random, Instant now) {
        Map<String, UUID> idByName = new LinkedHashMap<>();
        List<Object[]> rows = new ArrayList<>();
        for (PlaceSeed place : places) {
            UUID id = UUID.randomUUID();
            idByName.put(place.name(), id);
            UUID creator =
                    context.activeUserIds().get(random.nextInt(context.activeUserIds().size()));
            Instant createdAt = now.minus(Duration.ofDays(60 + random.nextInt(120)));
            boolean decided =
                    !"active".equals(place.status()) || "unbanned".equals(place.history());
            Instant statusAt =
                    decided ? createdAt.plus(Duration.ofDays(5 + random.nextInt(25))) : null;
            rows.add(
                    new Object[] {
                        id,
                        place.name(),
                        place.name(),
                        place.status(),
                        place.statusNote(),
                        statusAt == null ? null : Timestamp.from(statusAt),
                        decided ? context.adminId() : null,
                        creator,
                        Timestamp.from(createdAt)
                    });
        }
        jdbc.batchUpdate(
                "INSERT INTO places (id, name, normalized_name, status, status_note, status_at,"
                        + " status_by, created_by, created_at) VALUES (?, ?,"
                        + " fn_normalise_place_name(?), ?::place_status, ?, ?, ?, ?, ?)",
                rows);
        return idByName;
    }

    // ---------------------------------------------------------------- alt text

    private int writeAltTexts(Context context) {
        List<Object[]> updates = new ArrayList<>();
        Map<UUID, String> captionByPost = new HashMap<>();
        context.publishedPosts().forEach(post -> captionByPost.put(post.id(), post.caption()));
        for (Map.Entry<UUID, List<MediaRow>> entry : context.mediaByPost().entrySet()) {
            String caption = captionByPost.get(entry.getKey());
            List<MediaRow> media = entry.getValue();
            for (MediaRow row : media) {
                // About two items in five carry alt text, as authors skip the field often.
                if ((Math.floorMod(row.id().hashCode(), 5)) > 1) {
                    continue;
                }
                updates.add(new Object[] {altTextFor(caption, row, media.size()), row.id()});
            }
        }
        jdbc.batchUpdate("UPDATE post_media SET alt_text = ? WHERE id = ?", updates);
        return updates.size();
    }

    private static String altTextFor(String caption, MediaRow row, int count) {
        String subject = caption == null ? "" : caption.replaceAll("[#@]\\S+", "").strip();
        if (subject.length() > 90) {
            subject = subject.substring(0, 90).strip();
        }
        String kind = "video".equals(row.mediaType()) ? "Video" : "Photo";
        String prefix = count > 1 ? kind + " " + (row.position() + 1) + " of " + count : kind;
        return subject.isEmpty() ? prefix + " shared with the post" : prefix + ": " + subject;
    }

    // ---------------------------------------------------------------- tag preferences

    private void applyTagPreferences(SeedContent content, Map<String, UUID> usersByUsername) {
        List<Object[]> permissions = new ArrayList<>();
        List<Object[]> approvals = new ArrayList<>();
        int index = 0;
        for (UserSeed user : content.users()) {
            index++;
            if (user.isQaAccount()) {
                continue;
            }
            UUID id = usersByUsername.get(user.username());
            if (index % 10 == 1) {
                permissions.add(new Object[] {"following", id});
            } else if (index % 14 == 2) {
                permissions.add(new Object[] {"nobody", id});
            }
            if (index % 7 == 3) {
                approvals.add(new Object[] {id});
            }
        }
        jdbc.batchUpdate(
                "UPDATE user_settings SET tag_permission = ?::tag_permission WHERE user_id = ?",
                permissions);
        jdbc.batchUpdate(
                "UPDATE user_settings SET manual_tag_approval = TRUE WHERE user_id = ?", approvals);
    }

    // ---------------------------------------------------------------- tags

    private record TagRow(
            UUID id,
            UUID postId,
            UUID ownerId,
            UUID taggedId,
            boolean approved,
            boolean approvedLater,
            Instant createdAt,
            boolean keptInDatabase) {}

    private record TagSettings(String permission, boolean manualApproval) {}

    private Map<UUID, TagSettings> loadTagSettings() {
        Map<UUID, TagSettings> settings = new HashMap<>();
        jdbc.query(
                "SELECT user_id, tag_permission::text AS permission, manual_tag_approval FROM"
                        + " user_settings",
                rs -> {
                    settings.put(
                            (UUID) rs.getObject("user_id"),
                            new TagSettings(
                                    rs.getString("permission"),
                                    rs.getBoolean("manual_tag_approval")));
                });
        return settings;
    }

    private List<TagRow> writeTags(Context context, Random random, Instant now) {
        Map<UUID, TagSettings> settings = loadTagSettings();
        List<UUID> manualUsers =
                context.activeUserIds().stream()
                        .filter(
                                id ->
                                        settings.getOrDefault(
                                                        id, new TagSettings("everyone", false))
                                                .manualApproval())
                        .toList();
        List<TagRow> tags = new ArrayList<>();
        List<Object[]> rows = new ArrayList<>();
        int index = 0;
        int removedSoFar = 0;
        int manualTags = 0;
        int settledTags = 0;
        for (PostRow post : context.publishedPosts()) {
            List<MediaRow> media = context.mediaByPost().get(post.id());
            if (media == null || index++ % TAG_EVERY_NTH_POST != 0) {
                continue;
            }
            int wanted = 1 + random.nextInt(3);
            Set<UUID> taken = new HashSet<>();
            for (int attempt = 0; attempt < 12 && taken.size() < wanted; attempt++) {
                // The first eighteen tagged posts each tag an account that approves its tags, so
                // the pending and the approved-later states are never a matter of chance.
                UUID candidate =
                        (tags.size() < 18 && taken.isEmpty() && !manualUsers.isEmpty())
                                ? manualUsers.get(random.nextInt(manualUsers.size()))
                                : context.activeUserIds()
                                        .get(random.nextInt(context.activeUserIds().size()));
                TagSettings candidateSettings =
                        settings.getOrDefault(candidate, new TagSettings("everyone", false));
                if (candidate.equals(post.ownerId())
                        || taken.contains(candidate)
                        || context.blocked(candidate, post.ownerId())
                        || "nobody".equals(candidateSettings.permission())
                        || ("following".equals(candidateSettings.permission())
                                && !context.follows(candidate, post.ownerId()))) {
                    continue;
                }
                taken.add(candidate);
                MediaRow target = media.get(random.nextInt(media.size()));
                Instant createdAt =
                        earlier(
                                post.publishedAt().plus(Duration.ofMinutes(5 + random.nextInt(55))),
                                now);
                // Two of every three tags on an account that approves its tags wait for it; the
                // third was approved after the fact.
                boolean pending = candidateSettings.manualApproval() && manualTags++ % 3 != 2;
                boolean approvedLater = candidateSettings.manualApproval() && !pending;
                boolean removed =
                        !pending
                                && removedSoFar < TAGS_REMOVED_AFTER_THE_FACT
                                && settledTags++ % 6 == 5;
                if (removed) {
                    removedSoFar++;
                }
                UUID tagId = UUID.randomUUID();
                Instant approvedAt =
                        pending
                                ? null
                                : earlier(
                                        approvedLater
                                                ? createdAt.plus(
                                                        Duration.ofMinutes(
                                                                10 + random.nextInt(600)))
                                                : createdAt,
                                        now);
                tags.add(
                        new TagRow(
                                tagId,
                                post.id(),
                                post.ownerId(),
                                candidate,
                                !pending,
                                approvedLater,
                                createdAt,
                                !removed));
                if (!removed) {
                    rows.add(
                            new Object[] {
                                tagId,
                                post.id(),
                                target.id(),
                                candidate,
                                coordinate(random),
                                coordinate(random),
                                pending ? "pending" : "approved",
                                post.ownerId(),
                                Timestamp.from(createdAt),
                                approvedAt == null ? null : Timestamp.from(approvedAt)
                            });
                }
            }
        }
        jdbc.batchUpdate(
                "INSERT INTO post_user_tags (id, post_id, post_media_id, tagged_user_id,"
                        + " x_position, y_position, status, created_by, created_at, approved_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?::tag_status, ?, ?, ?)",
                rows);
        return tags;
    }

    private static java.math.BigDecimal coordinate(Random random) {
        return java.math.BigDecimal.valueOf(0.1 + random.nextDouble() * 0.8)
                .setScale(4, java.math.RoundingMode.HALF_UP);
    }

    private static Instant earlier(Instant candidate, Instant limit) {
        return candidate.isAfter(limit) ? limit : candidate;
    }

    // ---------------------------------------------------------------- collaboration

    private record CollaborationRow(
            UUID postId,
            UUID ownerId,
            UUID userId,
            String status,
            Instant invitedAt,
            Instant respondedAt,
            Instant acceptedAt) {}

    private List<CollaborationRow> writeCollaborations(
            Context context, Random random, Instant now) {
        List<CollaborationRow> collaborations = new ArrayList<>();
        Map<UUID, Set<UUID>> usedByPost = new HashMap<>();
        int stateIndex = 0;
        int postIndex = 0;
        for (PostRow post : context.publishedPosts()) {
            if (stateIndex >= COLLABORATION_STATES.length) {
                break;
            }
            if (context.mediaByPost().get(post.id()) == null
                    || postIndex++ % COLLABORATION_EVERY_NTH_POST != 0) {
                continue;
            }
            int perPost = random.nextDouble() < 0.25 ? 2 : 1;
            for (int slot = 0; slot < perPost && stateIndex < COLLABORATION_STATES.length; slot++) {
                UUID collaborator = pickCollaborator(post, context, random, usedByPost);
                if (collaborator == null) {
                    break;
                }
                usedByPost.computeIfAbsent(post.id(), id -> new HashSet<>()).add(collaborator);
                String status = COLLABORATION_STATES[stateIndex++];
                Instant invitedAt =
                        earlier(
                                post.publishedAt().plus(Duration.ofMinutes(2 + random.nextInt(28))),
                                now);
                Instant respondedAt =
                        "invited".equals(status)
                                ? null
                                : earlier(
                                        invitedAt.plus(
                                                Duration.ofMinutes(60 + random.nextInt(2_700))),
                                        now);
                Instant acceptedAt =
                        "accepted".equals(status) || "left".equals(status) ? respondedAt : null;
                if ("left".equals(status)) {
                    // Accepted first and left later; the acceptance time stays for attribution.
                    acceptedAt = respondedAt;
                    respondedAt =
                            earlier(
                                    respondedAt.plus(Duration.ofHours(6 + random.nextInt(60))),
                                    now);
                }
                collaborations.add(
                        new CollaborationRow(
                                post.id(),
                                post.ownerId(),
                                collaborator,
                                status,
                                invitedAt,
                                respondedAt,
                                acceptedAt));
            }
        }
        List<Object[]> rows = new ArrayList<>();
        for (CollaborationRow row : collaborations) {
            rows.add(
                    new Object[] {
                        row.postId(),
                        row.userId(),
                        row.status(),
                        row.ownerId(),
                        Timestamp.from(row.invitedAt()),
                        row.respondedAt() == null ? null : Timestamp.from(row.respondedAt()),
                        row.acceptedAt() == null ? null : Timestamp.from(row.acceptedAt())
                    });
        }
        jdbc.batchUpdate(
                "INSERT INTO post_collaborators (post_id, user_id, status, invited_by, invited_at,"
                        + " responded_at, accepted_at) VALUES (?, ?, ?::collaboration_status, ?, ?,"
                        + " ?, ?)",
                rows);
        return collaborations;
    }

    private UUID pickCollaborator(
            PostRow post, Context context, Random random, Map<UUID, Set<UUID>> usedByPost) {
        List<UUID> users = context.activeUserIds();
        for (int attempt = 0; attempt < 40; attempt++) {
            UUID candidate = users.get(random.nextInt(users.size()));
            boolean plausible =
                    context.follows(candidate, post.ownerId())
                            || context.follows(post.ownerId(), candidate);
            // Attempts beyond the first thirty accept any unrelated account, so a sparse follow
            // graph can still fill the states.
            if (!plausible && attempt < 30) {
                continue;
            }
            if (candidate.equals(post.ownerId())
                    || context.blocked(candidate, post.ownerId())
                    || usedByPost.getOrDefault(post.id(), Set.of()).contains(candidate)) {
                continue;
            }
            return candidate;
        }
        return null;
    }

    // ---------------------------------------------------------------- mentions

    private record MentionRow(UUID postId, UUID ownerId, UUID mentionedId, Instant at) {}

    private List<MentionRow> writeMentions(Context context, Random random) {
        List<MentionRow> mentions = new ArrayList<>();
        List<Object[]> captionUpdates = new ArrayList<>();
        List<Object[]> rows = new ArrayList<>();
        int index = 0;
        for (PostRow post : context.publishedPosts()) {
            if (index++ % MENTION_EVERY_NTH_POST != 0 || post.caption() == null) {
                continue;
            }
            UUID mentioned = pickMentionTarget(post.ownerId(), context, random);
            if (mentioned == null) {
                continue;
            }
            String handle = context.usernameById().get(mentioned);
            String caption = post.caption().strip() + " @" + handle;
            if (caption.length() > 2_200) {
                continue;
            }
            captionUpdates.add(new Object[] {caption, post.id()});
            rows.add(
                    new Object[] {
                        post.id(), mentioned, handle, Timestamp.from(post.publishedAt())
                    });
            mentions.add(new MentionRow(post.id(), post.ownerId(), mentioned, post.publishedAt()));
        }
        jdbc.batchUpdate("UPDATE posts SET caption = ? WHERE id = ?", captionUpdates);
        jdbc.batchUpdate(
                "INSERT INTO post_mentions (post_id, mentioned_user_id, handle, created_at) VALUES"
                        + " (?, ?, ?, ?)",
                rows);

        List<Object[]> commentUpdates = new ArrayList<>();
        List<Object[]> commentRows = new ArrayList<>();
        int commentIndex = 0;
        List<Object[]> comments = new ArrayList<>();
        jdbc.query(
                "SELECT id, user_id, content, created_at FROM comments WHERE deleted_at IS NULL"
                        + " AND admin_removed_at IS NULL AND moderation_status = 'approved' ORDER BY"
                        + " id",
                rs -> {
                    comments.add(
                            new Object[] {
                                rs.getObject("id"),
                                rs.getObject("user_id"),
                                rs.getString("content"),
                                rs.getTimestamp("created_at")
                            });
                });
        for (Object[] comment : comments) {
            if (commentIndex++ % MENTION_EVERY_NTH_COMMENT != 0) {
                continue;
            }
            UUID author = (UUID) comment[1];
            String content = (String) comment[2];
            UUID mentioned = pickMentionTarget(author, context, random);
            if (mentioned == null || content == null || content.length() > 900) {
                continue;
            }
            String handle = context.usernameById().get(mentioned);
            commentUpdates.add(new Object[] {content.strip() + " @" + handle, comment[0]});
            commentRows.add(new Object[] {comment[0], mentioned, handle, comment[3]});
        }
        jdbc.batchUpdate("UPDATE comments SET content = ? WHERE id = ?", commentUpdates);
        jdbc.batchUpdate(
                "INSERT INTO comment_mentions (comment_id, mentioned_user_id, handle, created_at)"
                        + " VALUES (?, ?, ?, ?)",
                commentRows);
        return mentions;
    }

    // An account the author follows or is followed by, so the mention reads as a real one, and
    // never one across a block.
    private UUID pickMentionTarget(UUID author, Context context, Random random) {
        List<UUID> users = context.activeUserIds();
        for (int attempt = 0; attempt < 30; attempt++) {
            UUID candidate = users.get(random.nextInt(users.size()));
            if (candidate.equals(author)
                    || context.blocked(candidate, author)
                    || !context.usernameById().containsKey(candidate)) {
                continue;
            }
            if (context.follows(author, candidate)
                    || context.follows(candidate, author)
                    || attempt > 20) {
                return candidate;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- scheduled posts

    private int writeScheduledPosts(Context context, Random random, Instant now) {
        // One reusable media asset per account that has a published image or video post.
        Map<UUID, UUID> assetByOwner = new LinkedHashMap<>();
        Map<UUID, String> typeByOwner = new HashMap<>();
        jdbc.query(
                "SELECT DISTINCT ON (p.user_id) p.user_id, pm.media_asset_id, p.post_type::text AS"
                        + " post_type FROM posts p JOIN post_media pm ON pm.post_id = p.id JOIN users"
                        + " u ON u.id = p.user_id WHERE p.status = 'published' AND p.deleted_at IS NULL"
                        + " AND p.post_type IN ('image', 'video') AND u.status = 'active' ORDER BY"
                        + " p.user_id, p.id",
                rs -> {
                    UUID owner = (UUID) rs.getObject("user_id");
                    assetByOwner.put(owner, (UUID) rs.getObject("media_asset_id"));
                    typeByOwner.put(owner, rs.getString("post_type"));
                });
        List<UUID> owners = new ArrayList<>(assetByOwner.keySet());
        int written = 0;
        for (int i = 0; i < SCHEDULED_POSTS && !owners.isEmpty(); i++) {
            UUID owner = owners.get(random.nextInt(owners.size()));
            UUID postId = UUID.randomUUID();
            Instant createdAt = now.minus(Duration.ofMinutes(30 + random.nextInt(900)));
            Instant dueAt =
                    now.plus(Duration.ofMinutes(SCHEDULED_AHEAD_MINUTES[i]))
                            .truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
            jdbc.update(
                    "INSERT INTO posts (id, user_id, caption, post_type, status, created_at,"
                            + " scheduled_at) VALUES (?, ?, ?, ?::post_type, 'scheduled'::post_status,"
                            + " ?, ?)",
                    postId,
                    owner,
                    SCHEDULED_CAPTIONS.get(i),
                    typeByOwner.get(owner),
                    Timestamp.from(createdAt),
                    Timestamp.from(dueAt));
            jdbc.update(
                    "INSERT INTO post_media (id, post_id, media_asset_id, position, alt_text,"
                            + " created_at) VALUES (?, ?, ?, 0, ?, ?)",
                    UUID.randomUUID(),
                    postId,
                    assetByOwner.get(owner),
                    i % 2 == 0 ? "Scheduled " + typeByOwner.get(owner) + " for the post" : null,
                    Timestamp.from(createdAt));
            written++;
            signalLedger.record(
                    new UserEventImport(
                            signalId("post_schedule", postId.toString()),
                            owner,
                            UserEventType.POST_SCHEDULE,
                            "post",
                            postId,
                            "{\"leadMinutes\":"
                                    + Math.max(0, Duration.between(createdAt, dueAt).toMinutes())
                                    + "}",
                            createdAt));
        }
        return written;
    }

    // ---------------------------------------------------------------- signals

    private void recordSignals(
            Context context,
            List<TagRow> tags,
            List<CollaborationRow> collaborations,
            List<MentionRow> mentions,
            Instant now,
            Random random) {
        for (PostRow post : context.publishedPosts()) {
            record(
                    "post_publish",
                    post.id().toString(),
                    post.ownerId(),
                    UserEventType.POST_PUBLISH,
                    "post",
                    post.id(),
                    "{\"source\":\"owner\"}",
                    post.publishedAt());
        }
        recordPostsInState("draft", UserEventType.POST_DRAFT_SAVE, "updated_at", now);
        recordPostsInState("archived", UserEventType.POST_ARCHIVE, "updated_at", now);

        List<PostRow> published = context.publishedPosts();
        for (int i = 0; i < UNARCHIVE_SIGNALS && i < published.size(); i++) {
            PostRow post = published.get(Math.floorMod(random.nextInt(), published.size()));
            Instant at = earlier(post.publishedAt().plus(Duration.ofDays(2)), now);
            record(
                    "post_archive_cycle",
                    post.id() + ":" + i,
                    post.ownerId(),
                    UserEventType.POST_ARCHIVE,
                    "post",
                    post.id(),
                    "",
                    at);
            record(
                    "post_unarchive",
                    post.id() + ":" + i,
                    post.ownerId(),
                    UserEventType.POST_UNARCHIVE,
                    "post",
                    post.id(),
                    "",
                    earlier(at.plus(Duration.ofHours(5)), now));
        }

        List<UUID> failedDrafts = new ArrayList<>();
        List<UUID> failedOwners = new ArrayList<>();
        List<Instant> failedAt = new ArrayList<>();
        jdbc.query(
                FAILED_SCHEDULE_DRAFTS_SQL,
                rs -> {
                    failedDrafts.add((UUID) rs.getObject("id"));
                    failedOwners.add((UUID) rs.getObject("user_id"));
                    failedAt.add(earlier(rs.getTimestamp("updated_at").toInstant(), now));
                });
        for (int i = 0; i < failedDrafts.size(); i++) {
            record(
                    "post_schedule_failed",
                    failedDrafts.get(i).toString(),
                    failedOwners.get(i),
                    UserEventType.POST_SCHEDULE_FAILED,
                    "post",
                    failedDrafts.get(i),
                    "{\"reason\":\"" + FAILED_SCHEDULE_REASONS.get(i) + "\"}",
                    failedAt.get(i));
        }

        for (TagRow tag : tags) {
            record(
                    "post_tag_add",
                    tag.id().toString(),
                    tag.ownerId(),
                    UserEventType.POST_TAG_ADD,
                    "post",
                    tag.postId(),
                    "",
                    tag.createdAt());
            if (tag.approvedLater()) {
                record(
                        "post_tag_approve",
                        tag.id().toString(),
                        tag.taggedId(),
                        UserEventType.POST_TAG_APPROVE,
                        "post",
                        tag.postId(),
                        "",
                        earlier(tag.createdAt().plus(Duration.ofHours(3)), now));
            }
            if (!tag.keptInDatabase()) {
                record(
                        "post_tag_remove",
                        tag.id().toString(),
                        tag.id().hashCode() % 2 == 0 ? tag.ownerId() : tag.taggedId(),
                        UserEventType.POST_TAG_REMOVE,
                        "post",
                        tag.postId(),
                        "",
                        earlier(tag.createdAt().plus(Duration.ofDays(1)), now));
            }
        }

        for (CollaborationRow row : collaborations) {
            String key = row.postId() + ":" + row.userId();
            record(
                    "post_collab_invite",
                    key,
                    row.ownerId(),
                    UserEventType.POST_COLLAB_INVITE,
                    "post",
                    row.postId(),
                    "",
                    row.invitedAt());
            if ("accepted".equals(row.status()) || "left".equals(row.status())) {
                record(
                        "post_collab_accept",
                        key,
                        row.userId(),
                        UserEventType.POST_COLLAB_ACCEPT,
                        "post",
                        row.postId(),
                        "",
                        row.acceptedAt());
            }
            if ("declined".equals(row.status())) {
                record(
                        "post_collab_decline",
                        key,
                        row.userId(),
                        UserEventType.POST_COLLAB_DECLINE,
                        "post",
                        row.postId(),
                        "",
                        row.respondedAt());
            }
            if ("left".equals(row.status())) {
                record(
                        "post_collab_leave",
                        key,
                        row.userId(),
                        UserEventType.POST_COLLAB_LEAVE,
                        "post",
                        row.postId(),
                        "",
                        row.respondedAt());
            }
        }

        for (MentionRow mention : mentions) {
            record(
                    "post_mention",
                    mention.postId() + ":" + mention.mentionedId(),
                    mention.ownerId(),
                    UserEventType.POST_MENTION,
                    "user",
                    mention.mentionedId(),
                    "",
                    mention.at());
        }
    }

    private void recordPostsInState(
            String status, UserEventType type, String timeColumn, Instant now) {
        jdbc.query(
                "SELECT id, user_id, "
                        + timeColumn
                        + " AS at FROM posts WHERE status = '"
                        + status
                        + "' AND deleted_at IS NULL ORDER BY id",
                rs -> {
                    UUID id = (UUID) rs.getObject("id");
                    record(
                            "post_" + status,
                            id.toString(),
                            (UUID) rs.getObject("user_id"),
                            type,
                            "post",
                            id,
                            "",
                            earlier(rs.getTimestamp("at").toInstant(), now));
                });
    }

    private void record(
            String kind,
            String key,
            UUID userId,
            UserEventType type,
            String entityType,
            UUID entityId,
            String metadata,
            Instant at) {
        signalLedger.record(
                new UserEventImport(
                        signalId(kind, key), userId, type, entityType, entityId, metadata, at));
    }

    private static UUID signalId(String kind, String key) {
        return UUID.nameUUIDFromBytes(
                ("seed_post_creation:" + kind + ":" + key).getBytes(StandardCharsets.UTF_8));
    }
}
