package com.app.common.seed.writer;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.app.common.seed.time.SeedTimeline;
import com.app.modules.notification.config.NotificationProperties;
import com.app.modules.notification.entity.enums.NotificationType;
import com.app.modules.notification.repository.NotificationAggregationRepository;
import com.app.modules.notification.service.NotificationDraft;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Seeds the activity feed: {@code notifications}, {@code notification_actors} and {@code
 * notification_seen_states}.
 *
 * <p>Every event is read back from a table an earlier writer populated - {@link
 * SocialGraphSeedWriter}'s {@code follows}, {@link CommentSeedWriter}'s {@code comments}, {@link
 * StorySeedWriter}'s {@code story_views}, {@link EngagementSeedWriter}'s {@code post_likes} and
 * {@code comment_likes}, {@link ModerationSeedWriter}'s {@code user_warnings} and {@code
 * admin_actions}, and {@link SupportSeedWriter}'s answered tickets - and shaped exactly as its
 * production producer shapes it: the consumer's entity, post and actor, and for a platform notice
 * the reason and the audit row. Direct messages left the feed, so no {@code message} row is
 * written.
 *
 * <p>Rows are written through {@link NotificationAggregationRepository}, the SQL production runs,
 * so a seeded group is shaped exactly like a live one: the same partial unique index serialises the
 * members of a group, the same trigger counts them, and the same join moves the group to its newest
 * actor. Production stamps every row with the database clock; a seed needs history, so each row is
 * backdated right after it is written - {@code created_at}, {@code group_started_at}, {@code
 * activity_at}, {@code read_at} and every member's {@code acted_at}. Events are replayed per
 * recipient and group key in time order and split into windows by the production rule, a window
 * accepting actors for {@code app.notification.aggregation-window} from its first, and a group is
 * backdated before the next window of its key opens, so the next write finds it closed exactly as
 * production would.
 *
 * <p>A handful of showcase accounts get a deliberately shaped feed so every state the client
 * renders can be reached from a fresh seed:
 *
 * <ul>
 *   <li>{@value #LIKES_SHOWCASE}: like groups of every size from one actor to every liker of the
 *       account's most-liked post, a verified newest actor, follow groups of five and of one (a
 *       follower it does not follow back), one soft-deleted row and an unseen count between 1 and
 *       99;
 *   <li>{@value #REQUESTS_SHOWCASE}: a private account holding pending follow requests;
 *   <li>{@value #UNAVAILABLE_SHOWCASE}: a like group on a post that is no longer published;
 *   <li>{@value #OVERFLOW_SHOWCASE}: no seen watermark at all, so the badge reads 99+;
 *   <li>{@value #QUIET_SHOWCASE}: a small feed with an ordinary badge.
 * </ul>
 *
 * <p>A mention of a showcase account on a private account's post the account cannot see
 * demonstrates an unavailable target. Mentions in general have no real source, since no seed
 * content encodes a literal {@code @username}: {@code mention_post} and {@code mention_comment} are
 * synthetic, a small fixed-size sample pairing existing posts and comments with a random other
 * account, kept so both enum values clear {@link com.app.common.seed.SeedRunner}'s coverage floor.
 * No production code path produces {@code mention_post} today.
 *
 * <p>Every seeded account gets a seen state: a few rows unseen, a few more above the previous
 * watermark so the "new" section is not empty on first open, and the rest seen.
 */
@Slf4j
@Service
@Profile("seed & (dev | prod)")
@RequiredArgsConstructor
public class NotificationSeedWriter {

    // Deliberately separate from SeedTimeline's own Random stream (used only for timestamps): this
    // stream drives the downsampling, the mention synthesis, the read split and the watermarks.
    private static final long NOTIFICATION_RANDOM_SEED = 6_104_887L;

    // Budget for the follow, comment and story-view pool before aggregation. Showcase accounts
    // and pending requests are exempt, so the rarest values keep clearing the coverage floor.
    private static final int POOL_EVENT_BUDGET = 4_000;
    private static final double UNREAD_PROBABILITY = 0.30;

    // Likes are the largest naturally occurring pools, so each gets its own event budget rather
    // than a share of the pool above, which it would otherwise dominate.
    private static final int LIKE_POST_SAMPLE_BUDGET = 800;
    private static final int LIKE_COMMENT_SAMPLE_BUDGET = 800;
    private static final int MENTION_SAMPLE_SIZE = 30;

    static final String LIKES_SHOWCASE = "sophieg.design";
    static final String REQUESTS_SHOWCASE = "user_private";
    static final String UNAVAILABLE_SHOWCASE = "vivian.frontend";
    static final String OVERFLOW_SHOWCASE = "tucker.trailrunner";
    static final String QUIET_SHOWCASE = "caleb.browses";

    private static final List<String> SHOWCASE_ACCOUNTS =
            List.of(
                    LIKES_SHOWCASE,
                    REQUESTS_SHOWCASE,
                    UNAVAILABLE_SHOWCASE,
                    OVERFLOW_SHOWCASE,
                    QUIET_SHOWCASE);

    // Unseen rows and "new but already seen" rows per ordinary account, drawn uniformly.
    private static final int MAX_UNSEEN = 12;
    private static final int MIN_NEW_SEEN = 2;
    private static final int MAX_NEW_SEEN = 6;

    private static final String POST_ENTITY_TYPE = "post";
    private static final String COMMENT_ENTITY_TYPE = "comment";
    private static final String STORY_ENTITY_TYPE = "story";
    private static final String REPORT_ENTITY_TYPE = "report";
    private static final String WARNING_ENTITY_TYPE = "warning";
    private static final String SUPPORT_TICKET_ENTITY_TYPE = "support_ticket";
    // AdminServiceImpl.notifyContentRemoved anchors a removal notice to the audit row rather than
    // to the removed content, because the content is hidden by the time the recipient reads it.
    private static final String ADMIN_ACTION_ENTITY_TYPE = "admin_action";

    private static final String ACTIVE_USER = " u.status = 'active' AND u.deleted_at IS NULL";

    private final JdbcTemplate jdbc;
    private final NotificationAggregationRepository aggregation;
    private final NotificationProperties properties;
    private final PlatformTransactionManager transactionManager;

    /**
     * Writes the seeded feed, its actor memberships and every account's seen state, in one
     * transaction.
     *
     * <p>Must run after {@link SocialGraphSeedWriter}, {@link CommentSeedWriter}, {@link
     * StorySeedWriter}, {@link ModerationSeedWriter}, {@link EngagementSeedWriter}, {@link
     * SupportSeedWriter} and {@link VerificationSeedWriter} - every event is read back from tables
     * those writers populate, and the verified flag of an actor from the last of them.
     */
    public void write(SeedTimeline timeline) {
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> writeInTransaction(timeline.referenceNow()));
    }

    private void writeInTransaction(Instant now) {
        Random random = new Random(NOTIFICATION_RANDOM_SEED);
        Map<String, UUID> showcase = fetchShowcaseAccounts();
        Set<UUID> exempt = new HashSet<>(showcase.values());
        Set<UUID> verified = fetchVerifiedUsers();

        List<Event> shaped = new ArrayList<>();
        Set<String> reservedKeys = new HashSet<>();
        shapeShowcase(showcase, now, shaped, reservedKeys);

        List<Event> fixed = new ArrayList<>();
        fixed.addAll(fetchWarningEvents());
        fixed.addAll(fetchModerationEvents());
        fixed.addAll(fetchSupportAnswerEvents());
        fixed.addAll(fetchFollowRequestEvents());
        fixed.addAll(fetchMentionPostEvents(random));
        fixed.addAll(fetchMentionCommentEvents(random));

        List<Event> pool = new ArrayList<>();
        pool.addAll(fetchFollowEvents());
        pool.addAll(fetchCommentEvents());
        pool.addAll(fetchStoryViewEvents());
        List<Event> exemptPool = new ArrayList<>();
        List<Event> sampledPool = new ArrayList<>();
        for (Event event : withoutReserved(pool, reservedKeys)) {
            (exempt.contains(event.draft().recipientId()) ? exemptPool : sampledPool).add(event);
        }
        int budget = Math.max(POOL_EVENT_BUDGET - exemptPool.size(), 0);

        List<Event> all = new ArrayList<>(shaped);
        all.addAll(fixed);
        all.addAll(exemptPool);
        all.addAll(downsample(sampledPool, budget, random));
        all.addAll(
                downsample(
                        withoutReserved(fetchLikePostEvents(), reservedKeys),
                        LIKE_POST_SAMPLE_BUDGET,
                        random));
        all.addAll(
                downsample(
                        withoutReserved(fetchLikeCommentEvents(), reservedKeys),
                        LIKE_COMMENT_SAMPLE_BUDGET,
                        random));

        Counts counts = persist(all, verified, now, random);
        softDeleteOne(showcase.get(LIKES_SHOWCASE));
        int seenStates = writeSeenStates(showcase.get(OVERFLOW_SHOWCASE), now, random);
        log.info(
                "[seed] notifications: {} events -> {} rows ({} groups, {} single), {} actor"
                        + " memberships, {} seen states, {} shaped showcase events",
                all.size(),
                counts.groups() + counts.singles(),
                counts.groups(),
                counts.singles(),
                counts.members(),
                seenStates,
                shaped.size());
    }

    // Replays every event through the production write path. Aggregatable events are replayed per
    // recipient and group key in time order so each window lands in one group; every row is
    // backdated as soon as it is complete, so a later window of the same key finds it closed.
    private Counts persist(List<Event> events, Set<UUID> verified, Instant now, Random random) {
        Map<String, List<Event>> byGroupKey = new LinkedHashMap<>();
        List<Event> singles = new ArrayList<>();
        for (Event event : events) {
            if (event.groupKey() == null) {
                singles.add(event);
            } else {
                byGroupKey.computeIfAbsent(event.groupKey(), key -> new ArrayList<>()).add(event);
            }
        }

        Duration window = properties.aggregationWindow();
        List<Object[]> memberships = new ArrayList<>();
        int groups = 0;
        for (List<Event> keyed : byGroupKey.values()) {
            keyed.sort(Comparator.comparing(Event::at));
            for (List<Event> members : splitIntoWindows(keyed, window)) {
                UUID id = null;
                for (Event member : members) {
                    NotificationDraft draft = member.draft();
                    id =
                            aggregation
                                    .upsertGroup(
                                            draft,
                                            draft.type().category(),
                                            draft.type().aggregationKey(draft.entityId()),
                                            window,
                                            verified.contains(draft.actorId()))
                                    .id();
                    memberships.add(new Object[] {member.at(), id, draft.actorId()});
                }
                Event first = members.get(0);
                Event last = members.get(members.size() - 1);
                jdbc.update(
                        "UPDATE notifications SET created_at = ?, group_started_at = ?,"
                                + " activity_at = ?, read_at = ?, is_group_open = is_group_open"
                                + " AND ? > clock_timestamp() - make_interval(secs => ?)"
                                + " WHERE id = ?",
                        Timestamp.from(first.at()),
                        Timestamp.from(first.at()),
                        Timestamp.from(last.at()),
                        readAt(last, now, random),
                        Timestamp.from(first.at()),
                        (double) window.toSeconds(),
                        id);
                groups++;
            }
        }

        List<Object[]> singleStamps = new ArrayList<>();
        for (Event event : singles) {
            NotificationDraft draft = event.draft();
            UUID id =
                    aggregation.insertSingle(
                            draft,
                            draft.type().category(),
                            draft.actorId() != null && verified.contains(draft.actorId()));
            if (draft.actorId() != null) {
                aggregation.addMember(id, draft.actorId());
                memberships.add(new Object[] {event.at(), id, draft.actorId()});
            }
            singleStamps.add(
                    new Object[] {
                        Timestamp.from(event.at()),
                        Timestamp.from(event.at()),
                        readAt(event, now, random),
                        id
                    });
        }
        jdbc.batchUpdate(
                "UPDATE notifications SET created_at = ?, activity_at = ?, read_at = ? WHERE id = ?",
                singleStamps);
        jdbc.batchUpdate(
                "UPDATE notification_actors SET acted_at = ? WHERE notification_id = ?"
                        + " AND actor_id = ?",
                memberships.stream()
                        .map(row -> new Object[] {Timestamp.from((Instant) row[0]), row[1], row[2]})
                        .toList());
        return new Counts(groups, singles.size(), memberships.size());
    }

    // The production rule: a window accepts actors for the aggregation window from its first.
    private static List<List<Event>> splitIntoWindows(List<Event> sorted, Duration window) {
        List<List<Event>> windows = new ArrayList<>();
        List<Event> current = new ArrayList<>();
        Instant start = null;
        for (Event event : sorted) {
            if (start == null || !event.at().isBefore(start.plus(window))) {
                current = new ArrayList<>();
                windows.add(current);
                start = event.at();
            }
            current.add(event);
        }
        return windows;
    }

    private static Timestamp readAt(Event event, Instant now, Random random) {
        boolean read =
                event.read() != null ? event.read() : random.nextDouble() >= UNREAD_PROBABILITY;
        if (!read) {
            return null;
        }
        Instant at = event.at().plusSeconds(1 + random.nextInt(3_600));
        return Timestamp.from(at.isAfter(now) ? now : at);
    }

    // Reservoir-style uniform sample without replacement: shuffling the whole list and truncating
    // gives every event an equal chance of surviving, regardless of which type produced it.
    private static List<Event> downsample(List<Event> events, int budget, Random random) {
        if (events.size() <= budget) {
            return events;
        }
        List<Event> shuffled = new ArrayList<>(events);
        Collections.shuffle(shuffled, random);
        return shuffled.subList(0, budget);
    }

    private static List<Event> withoutReserved(List<Event> events, Set<String> reservedKeys) {
        return events.stream()
                .filter(
                        event ->
                                event.groupKey() == null
                                        || !reservedKeys.contains(event.groupKey()))
                .toList();
    }

    private Map<String, UUID> fetchShowcaseAccounts() {
        Map<String, UUID> accounts = new HashMap<>();
        jdbc.query(
                "SELECT username, id FROM users WHERE username = ANY (?)",
                rs -> {
                    accounts.put(rs.getString("username"), (UUID) rs.getObject("id"));
                },
                (Object) SHOWCASE_ACCOUNTS.toArray(String[]::new));
        return accounts;
    }

    private Set<UUID> fetchVerifiedUsers() {
        return new HashSet<>(
                jdbc.queryForList("SELECT id FROM users WHERE is_verified", UUID.class));
    }

    // The showcase feeds are placed relative to the seed clock, so "new", "today", "this week"
    // and "older" are all populated whenever the seed runs. Each group's key is reserved, so the
    // ordinary pools cannot merge into it and change its size.
    private void shapeShowcase(
            Map<String, UUID> showcase, Instant now, List<Event> shaped, Set<String> reserved) {
        UUID likes = showcase.get(LIKES_SHOWCASE);
        if (likes != null) {
            shapeLikeGroups(likes, now, shaped, reserved);
            shapeFollowGroups(likes, now, shaped, reserved);
        }
        UUID unavailable = showcase.get(UNAVAILABLE_SHOWCASE);
        if (unavailable != null) {
            shapeUnavailableLike(unavailable, now, shaped, reserved);
        }
        UUID requests = showcase.get(REQUESTS_SHOWCASE);
        if (requests != null) {
            shapePrivateMention(
                    requests,
                    List.of(showcase.get(LIKES_SHOWCASE), showcase.get(QUIET_SHOWCASE)),
                    now,
                    shaped);
        }
    }

    // Sizes one, two, three, fourteen and every liker of the most-liked post, newest first. The
    // members are ordered with verified likers last, so a verified account is the newest actor of
    // a group whenever one liked the post, which is what the verified filter matches on.
    private void shapeLikeGroups(
            UUID recipient, Instant now, List<Event> shaped, Set<String> reserved) {
        List<UUID> posts =
                jdbc.queryForList(
                        "SELECT p.id FROM posts p LEFT JOIN post_media pm ON pm.post_id = p.id"
                                + " AND pm.position = 0 LEFT JOIN media_assets ma"
                                + " ON ma.id = pm.media_asset_id WHERE p.user_id = ?"
                                + " AND p.status = 'published' AND p.deleted_at IS NULL"
                                + " ORDER BY p.like_count DESC,"
                                + " (ma.media_type = 'video') DESC NULLS LAST, p.id",
                        UUID.class,
                        recipient);
        int[] sizes = {Integer.MAX_VALUE, 14, 3, 2, 1};
        Duration[] ages = {
            Duration.ofMinutes(55),
            Duration.ofHours(6),
            Duration.ofDays(3),
            Duration.ofDays(12),
            Duration.ofDays(45)
        };
        // Unread, read, unread, read, unread: both states appear inside and below "new".
        boolean[] read = {false, true, false, true, false};
        Set<UUID> used = new HashSet<>();
        for (int i = 0; i < sizes.length; i++) {
            int needed = sizes[i] == Integer.MAX_VALUE ? 1 : sizes[i];
            for (UUID post : posts) {
                if (used.contains(post)) {
                    continue;
                }
                List<UUID> likers = activeLikers(post, recipient);
                if (likers.size() < needed) {
                    continue;
                }
                used.add(post);
                int size = Math.min(sizes[i], likers.size());
                addGroup(
                        shaped,
                        reserved,
                        recipient,
                        likers.subList(likers.size() - size, likers.size()),
                        NotificationType.LIKE_POST,
                        post,
                        now.minus(ages[i]),
                        read[i]);
                break;
            }
        }
    }

    // Verified likers sort last, so taking the tail keeps them in every group.
    private List<UUID> activeLikers(UUID postId, UUID ownerId) {
        return jdbc.queryForList(
                "SELECT pl.user_id FROM post_likes pl JOIN users u ON u.id = pl.user_id"
                        + " WHERE pl.post_id = ? AND pl.user_id <> ? AND"
                        + ACTIVE_USER
                        + " ORDER BY u.is_verified, pl.created_at, pl.user_id",
                UUID.class,
                postId,
                ownerId);
    }

    // A group of five three hours ago and a single follow six days ago from an account the
    // recipient does not follow back, so the follow-back action has a row to appear on.
    private void shapeFollowGroups(
            UUID recipient, Instant now, List<Event> shaped, Set<String> reserved) {
        List<UUID> notFollowedBack = new ArrayList<>();
        List<UUID> followedBack = new ArrayList<>();
        jdbc.query(
                "SELECT f.follower_id, EXISTS (SELECT 1 FROM follows b WHERE b.follower_id = ?"
                        + " AND b.following_id = f.follower_id AND b.status = 'accepted')"
                        + " AS followed_back FROM follows f JOIN users u ON u.id = f.follower_id"
                        + " WHERE f.following_id = ? AND f.status = 'accepted' AND"
                        + ACTIVE_USER
                        + " ORDER BY f.created_at, f.follower_id",
                rs -> {
                    UUID follower = (UUID) rs.getObject("follower_id");
                    (rs.getBoolean("followed_back") ? followedBack : notFollowedBack).add(follower);
                },
                recipient,
                recipient);
        List<UUID> everyone = new ArrayList<>(followedBack);
        everyone.addAll(notFollowedBack);
        if (notFollowedBack.isEmpty() || everyone.size() < 6) {
            return;
        }
        UUID single = notFollowedBack.get(0);
        everyone.remove(single);
        addGroup(
                shaped,
                reserved,
                recipient,
                List.of(single),
                NotificationType.FOLLOW,
                null,
                now.minus(Duration.ofDays(6)),
                false);
        addGroup(
                shaped,
                reserved,
                recipient,
                everyone.subList(0, 5),
                NotificationType.FOLLOW,
                null,
                now.minus(Duration.ofHours(3)),
                false);
    }

    // A like group on a post that has since left the feed, so the row renders an unavailable
    // target rather than a thumbnail.
    private void shapeUnavailableLike(
            UUID recipient, Instant now, List<Event> shaped, Set<String> reserved) {
        List<UUID> posts =
                jdbc.queryForList(
                        "SELECT id FROM posts WHERE user_id = ? AND (status <> 'published'"
                                + " OR deleted_at IS NOT NULL) AND like_count > 0"
                                + " ORDER BY like_count DESC, id",
                        UUID.class,
                        recipient);
        for (UUID post : posts) {
            List<UUID> likers = activeLikers(post, recipient);
            if (!likers.isEmpty()) {
                addGroup(
                        shaped,
                        reserved,
                        recipient,
                        likers.subList(Math.max(likers.size() - 3, 0), likers.size()),
                        NotificationType.LIKE_POST,
                        post,
                        now.minus(Duration.ofHours(30)),
                        false);
                return;
            }
        }
    }

    // The private account mentions a showcase account on a post that account cannot see, because
    // it does not follow the private author: the row exists and its target is unavailable.
    private void shapePrivateMention(
            UUID author, List<UUID> recipients, Instant now, List<Event> shaped) {
        List<UUID> posts =
                jdbc.queryForList(
                        "SELECT id FROM posts WHERE user_id = ? AND status = 'published'"
                                + " AND deleted_at IS NULL ORDER BY created_at DESC, id LIMIT 1",
                        UUID.class,
                        author);
        if (posts.isEmpty()) {
            return;
        }
        for (UUID recipient : recipients) {
            if (recipient == null) {
                continue;
            }
            Integer follows =
                    jdbc.queryForObject(
                            "SELECT count(*) FROM follows WHERE follower_id = ?"
                                    + " AND following_id = ? AND status = 'accepted'",
                            Integer.class,
                            recipient,
                            author);
            if (follows != null && follows == 0) {
                shaped.add(
                        new Event(
                                NotificationDraft.of(
                                        author,
                                        recipient,
                                        NotificationType.MENTION_POST,
                                        POST_ENTITY_TYPE,
                                        posts.get(0),
                                        posts.get(0)),
                                now.minus(Duration.ofHours(20)),
                                false));
                return;
            }
        }
    }

    // Members are spread evenly over fifty minutes from the start, so the newest actor is the last
    // in the list and even the largest group ends before the seed clock: a row in the future would
    // sit above every live row and above any watermark a client could advance to.
    private static void addGroup(
            List<Event> shaped,
            Set<String> reserved,
            UUID recipient,
            List<UUID> actors,
            NotificationType type,
            UUID postId,
            Instant start,
            boolean read) {
        long spacingSeconds = Math.max(1, Duration.ofMinutes(50).toSeconds() / actors.size());
        for (int i = 0; i < actors.size(); i++) {
            NotificationDraft draft =
                    type == NotificationType.FOLLOW
                            ? NotificationDraft.of(actors.get(i), recipient, type, null, null, null)
                            : NotificationDraft.of(
                                    actors.get(i),
                                    recipient,
                                    type,
                                    POST_ENTITY_TYPE,
                                    postId,
                                    postId);
            Event event = new Event(draft, start.plusSeconds(i * spacingSeconds), read);
            reserved.add(event.groupKey());
            shaped.add(event);
        }
    }

    // follow_status = 'accepted' is a follow from the follower; the entity is empty, matching
    // SocialNotificationConsumer.
    private List<Event> fetchFollowEvents() {
        List<Event> events = new ArrayList<>();
        jdbc.query(
                "SELECT follower_id, following_id, created_at FROM follows"
                        + " WHERE status = 'accepted'",
                rs -> {
                    events.add(
                            event(
                                    NotificationDraft.of(
                                            (UUID) rs.getObject("follower_id"),
                                            (UUID) rs.getObject("following_id"),
                                            NotificationType.FOLLOW,
                                            null,
                                            null,
                                            null),
                                    rs.getTimestamp("created_at")));
                });
        return events;
    }

    // Every pending follow keeps its request row: the badge and the head count them, and the
    // population is too small to sample.
    private List<Event> fetchFollowRequestEvents() {
        List<Event> events = new ArrayList<>();
        jdbc.query(
                "SELECT follower_id, following_id, created_at FROM follows"
                        + " WHERE status = 'pending'",
                rs -> {
                    events.add(
                            event(
                                    NotificationDraft.of(
                                            (UUID) rs.getObject("follower_id"),
                                            (UUID) rs.getObject("following_id"),
                                            NotificationType.FOLLOW_REQUEST,
                                            null,
                                            null,
                                            null),
                                    rs.getTimestamp("created_at")));
                });
        return events;
    }

    // A root comment notifies the post author and a reply the parent comment's author, never the
    // commenter themself, matching CommentNotificationConsumer.
    private List<Event> fetchCommentEvents() {
        List<Event> events = new ArrayList<>();
        jdbc.query(
                "SELECT c.id AS comment_id, c.user_id AS author_id, c.post_id,"
                        + " c.created_at, p.user_id AS post_author_id,"
                        + " parent.user_id AS parent_author_id"
                        + " FROM comments c JOIN posts p ON p.id = c.post_id"
                        + " LEFT JOIN comments parent ON parent.id = c.parent_id"
                        + " WHERE c.deleted_at IS NULL",
                rs -> {
                    UUID author = (UUID) rs.getObject("author_id");
                    UUID parentAuthor = (UUID) rs.getObject("parent_author_id");
                    UUID recipient =
                            parentAuthor != null
                                    ? parentAuthor
                                    : (UUID) rs.getObject("post_author_id");
                    if (recipient == null || recipient.equals(author)) {
                        return;
                    }
                    events.add(
                            event(
                                    NotificationDraft.of(
                                            author,
                                            recipient,
                                            parentAuthor != null
                                                    ? NotificationType.REPLY_COMMENT
                                                    : NotificationType.COMMENT_POST,
                                            COMMENT_ENTITY_TYPE,
                                            (UUID) rs.getObject("comment_id"),
                                            (UUID) rs.getObject("post_id")),
                                    rs.getTimestamp("created_at")));
                });
        return events;
    }

    // StorySeedWriter never records an owner's view of their own story. A story lives for a day,
    // so its views land in one group per story.
    private List<Event> fetchStoryViewEvents() {
        List<Event> events = new ArrayList<>();
        jdbc.query(
                "SELECT sv.story_id, sv.viewer_id, sv.viewed_at, s.user_id AS owner_id"
                        + " FROM story_views sv JOIN stories s ON s.id = sv.story_id",
                rs -> {
                    events.add(
                            event(
                                    NotificationDraft.of(
                                            (UUID) rs.getObject("viewer_id"),
                                            (UUID) rs.getObject("owner_id"),
                                            NotificationType.STORY_VIEW,
                                            STORY_ENTITY_TYPE,
                                            (UUID) rs.getObject("story_id"),
                                            null),
                                    rs.getTimestamp("viewed_at")));
                });
        return events;
    }

    private List<Event> fetchLikePostEvents() {
        List<Event> events = new ArrayList<>();
        jdbc.query(
                "SELECT pl.post_id, pl.user_id AS liker_id, pl.created_at,"
                        + " p.user_id AS author_id FROM post_likes pl JOIN posts p"
                        + " ON p.id = pl.post_id WHERE pl.user_id <> p.user_id",
                rs -> {
                    UUID post = (UUID) rs.getObject("post_id");
                    events.add(
                            event(
                                    NotificationDraft.of(
                                            (UUID) rs.getObject("liker_id"),
                                            (UUID) rs.getObject("author_id"),
                                            NotificationType.LIKE_POST,
                                            POST_ENTITY_TYPE,
                                            post,
                                            post),
                                    rs.getTimestamp("created_at")));
                });
        return events;
    }

    private List<Event> fetchLikeCommentEvents() {
        List<Event> events = new ArrayList<>();
        jdbc.query(
                "SELECT cl.comment_id, cl.user_id AS liker_id, cl.created_at,"
                        + " c.user_id AS author_id, c.post_id FROM comment_likes cl"
                        + " JOIN comments c ON c.id = cl.comment_id WHERE cl.user_id <> c.user_id",
                rs -> {
                    events.add(
                            event(
                                    NotificationDraft.of(
                                            (UUID) rs.getObject("liker_id"),
                                            (UUID) rs.getObject("author_id"),
                                            NotificationType.LIKE_COMMENT,
                                            COMMENT_ENTITY_TYPE,
                                            (UUID) rs.getObject("comment_id"),
                                            (UUID) rs.getObject("post_id")),
                                    rs.getTimestamp("created_at")));
                });
        return events;
    }

    // Shaped like AdminNotificationConsumer's warning notice: the warning as the entity, the audit
    // row that issued it, and no reason text, which the moderation block reads from the audit row.
    private List<Event> fetchWarningEvents() {
        List<Event> events = new ArrayList<>();
        jdbc.query(
                "SELECT id, user_id, admin_action_id, created_at FROM user_warnings",
                rs -> {
                    events.add(
                            event(
                                    NotificationDraft.systemNotice(
                                            (UUID) rs.getObject("user_id"),
                                            NotificationType.WARNING,
                                            WARNING_ENTITY_TYPE,
                                            (UUID) rs.getObject("id"),
                                            null,
                                            null,
                                            (UUID) rs.getObject("admin_action_id")),
                                    rs.getTimestamp("created_at")));
                });
        return events;
    }

    // Every moderation notice AdminServiceImpl sends, each with the reason the moderator gave and
    // the audit row it reports.
    private List<Event> fetchModerationEvents() {
        List<Event> events = new ArrayList<>();
        // post_removed and post_restored tell the post's owner, carrying the post twice as
        // AdminServiceImpl does.
        jdbc.query(
                "SELECT aa.id AS action_id, aa.action_type::text AS action_type, aa.reason,"
                        + " aa.created_at, p.id AS post_id, p.user_id AS owner_id"
                        + " FROM admin_actions aa JOIN posts p ON p.id = aa.target_entity_id"
                        + " WHERE aa.action_type IN ('remove_post', 'restore_post')",
                rs -> {
                    UUID post = (UUID) rs.getObject("post_id");
                    events.add(
                            event(
                                    NotificationDraft.systemNotice(
                                            (UUID) rs.getObject("owner_id"),
                                            "remove_post".equals(rs.getString("action_type"))
                                                    ? NotificationType.POST_REMOVED
                                                    : NotificationType.POST_RESTORED,
                                            POST_ENTITY_TYPE,
                                            post,
                                            post,
                                            rs.getString("reason"),
                                            (UUID) rs.getObject("action_id")),
                                    rs.getTimestamp("created_at")));
                });
        // Production sends report_post_removed only when the removed post is the report's own
        // entity. ModerationSeedWriter reuses one case-level report across every post a case
        // removes, so this reads "a post report against the same account" instead, which is what
        // the case narrative means.
        jdbc.query(
                "SELECT aa.id AS action_id, aa.report_id, aa.created_at, aa.target_entity_id"
                        + " AS post_id, r.reporter_id FROM admin_actions aa JOIN reports r"
                        + " ON r.id = aa.report_id JOIN posts p ON p.id = aa.target_entity_id"
                        + " WHERE aa.action_type = 'remove_post' AND r.report_type = 'post'"
                        + " AND r.reporter_id <> p.user_id",
                rs -> {
                    events.add(
                            event(
                                    NotificationDraft.systemNotice(
                                            (UUID) rs.getObject("reporter_id"),
                                            NotificationType.REPORT_POST_REMOVED,
                                            REPORT_ENTITY_TYPE,
                                            (UUID) rs.getObject("report_id"),
                                            (UUID) rs.getObject("post_id"),
                                            null,
                                            (UUID) rs.getObject("action_id")),
                                    rs.getTimestamp("created_at")));
                });
        // A dismissal without a linked report has no reporter to tell.
        jdbc.query(
                "SELECT aa.id AS action_id, aa.report_id, aa.reason, aa.created_at, r.reporter_id"
                        + " FROM admin_actions aa JOIN reports r ON r.id = aa.report_id"
                        + " WHERE aa.action_type = 'dismiss_report'",
                rs -> {
                    events.add(
                            event(
                                    NotificationDraft.systemNotice(
                                            (UUID) rs.getObject("reporter_id"),
                                            NotificationType.REPORT_DISMISSED,
                                            REPORT_ENTITY_TYPE,
                                            (UUID) rs.getObject("report_id"),
                                            null,
                                            rs.getString("reason"),
                                            (UUID) rs.getObject("action_id")),
                                    rs.getTimestamp("created_at")));
                });
        collectContentRemoved(
                events,
                "SELECT aa.id AS action_id, aa.reason, aa.created_at, c.user_id AS owner_id"
                        + " FROM admin_actions aa JOIN comments c ON c.id = aa.target_entity_id"
                        + " WHERE aa.action_type = 'remove_comment'",
                NotificationType.COMMENT_REMOVED);
        collectContentRemoved(
                events,
                "SELECT aa.id AS action_id, aa.reason, aa.created_at, s.user_id AS owner_id"
                        + " FROM admin_actions aa JOIN stories s ON s.id = aa.target_entity_id"
                        + " WHERE aa.action_type = 'remove_story'",
                NotificationType.STORY_REMOVED);
        collectContentRemoved(
                events,
                "SELECT aa.id AS action_id, aa.reason, aa.created_at, m.sender_id AS owner_id"
                        + " FROM admin_actions aa JOIN messages m ON m.id = aa.target_entity_id"
                        + " WHERE aa.action_type = 'remove_message'",
                NotificationType.MESSAGE_REMOVED);
        return events;
    }

    private void collectContentRemoved(List<Event> events, String sql, NotificationType type) {
        jdbc.query(
                sql,
                rs -> {
                    UUID owner = (UUID) rs.getObject("owner_id");
                    if (owner == null) {
                        return;
                    }
                    UUID action = (UUID) rs.getObject("action_id");
                    events.add(
                            event(
                                    NotificationDraft.systemNotice(
                                            owner,
                                            type,
                                            ADMIN_ACTION_ENTITY_TYPE,
                                            action,
                                            null,
                                            rs.getString("reason"),
                                            action),
                                    rs.getTimestamp("created_at")));
                });
    }

    // A support answer is a platform notice: no staff actor and no audit row, matching
    // SupportTicketServiceImpl. A public ticket that never resolved to an account has nobody to
    // notify in-product.
    private List<Event> fetchSupportAnswerEvents() {
        List<Event> events = new ArrayList<>();
        jdbc.query(
                "SELECT id, user_id, responded_at FROM support_tickets"
                        + " WHERE status IN ('answered', 'rejected') AND user_id IS NOT NULL"
                        + " AND responded_at IS NOT NULL",
                rs -> {
                    events.add(
                            event(
                                    NotificationDraft.systemNotice(
                                            (UUID) rs.getObject("user_id"),
                                            NotificationType.SUPPORT_TICKET_UPDATE,
                                            SUPPORT_TICKET_ENTITY_TYPE,
                                            (UUID) rs.getObject("id"),
                                            null,
                                            null,
                                            null),
                                    rs.getTimestamp("responded_at")));
                });
        return events;
    }

    // Synthetic: a published post's author mentions a random other account in it.
    private List<Event> fetchMentionPostEvents(Random random) {
        List<Object[]> posts = new ArrayList<>();
        jdbc.query(
                "SELECT id, user_id, created_at FROM posts WHERE status = 'published'"
                        + " ORDER BY id",
                rs -> {
                    UUID id = (UUID) rs.getObject("id");
                    posts.add(
                            new Object[] {
                                id, rs.getObject("user_id"), id, rs.getTimestamp("created_at")
                            });
                });
        return synthesizeMentions(posts, NotificationType.MENTION_POST, POST_ENTITY_TYPE, random);
    }

    // Synthetic: a visible comment's author mentions a random other account in it.
    private List<Event> fetchMentionCommentEvents(Random random) {
        List<Object[]> comments = new ArrayList<>();
        jdbc.query(
                "SELECT id, user_id, post_id, created_at FROM comments WHERE deleted_at IS NULL"
                        + " AND admin_removed_at IS NULL AND moderation_status = 'approved'"
                        + " ORDER BY id",
                rs -> {
                    comments.add(
                            new Object[] {
                                rs.getObject("id"),
                                rs.getObject("user_id"),
                                rs.getObject("post_id"),
                                rs.getTimestamp("created_at")
                            });
                });
        return synthesizeMentions(
                comments, NotificationType.MENTION_COMMENT, COMMENT_ENTITY_TYPE, random);
    }

    // Rows are {entityId, authorId, postId, createdAt}.
    private List<Event> synthesizeMentions(
            List<Object[]> sources, NotificationType type, String entityType, Random random) {
        List<Event> events = new ArrayList<>();
        List<UUID> users = jdbc.queryForList("SELECT id FROM users ORDER BY id", UUID.class);
        if (sources.isEmpty() || users.size() < 2) {
            return events;
        }
        for (int i = 0; i < MENTION_SAMPLE_SIZE; i++) {
            Object[] source = sources.get(random.nextInt(sources.size()));
            UUID author = (UUID) source[1];
            UUID mentioned = users.get(random.nextInt(users.size()));
            if (mentioned.equals(author)) {
                continue;
            }
            events.add(
                    event(
                            NotificationDraft.of(
                                    author,
                                    mentioned,
                                    type,
                                    entityType,
                                    (UUID) source[0],
                                    (UUID) source[2]),
                            (Timestamp) source[3]));
        }
        return events;
    }

    // The showcase account's oldest comment notification is removed from its feed, the way the
    // recipient's own delete would remove it.
    private void softDeleteOne(UUID recipient) {
        if (recipient == null) {
            return;
        }
        jdbc.queryForList(
                        "SELECT id FROM notifications WHERE recipient_id = ?"
                                + " AND type = 'comment_post' AND deleted_at IS NULL"
                                + " ORDER BY activity_at, id LIMIT 1",
                        UUID.class,
                        recipient)
                .forEach(id -> aggregation.softDelete(recipient, id));
    }

    // One state per account. The watermarks sit on live rows in feed order, (activity_at, id)
    // descending: a few rows above "seen" are unseen, a few more above "previous" are new but seen.
    // The overflow account has no watermark, so every row it has is unseen.
    private int writeSeenStates(UUID overflow, Instant now, Random random) {
        Map<UUID, List<Object[]>> feeds = new HashMap<>();
        jdbc.query(
                "SELECT recipient_id, activity_at, id FROM notifications WHERE deleted_at IS NULL"
                        + " ORDER BY recipient_id, activity_at DESC, id DESC",
                rs -> {
                    feeds.computeIfAbsent(
                                    (UUID) rs.getObject("recipient_id"), k -> new ArrayList<>())
                            .add(new Object[] {rs.getTimestamp("activity_at"), rs.getObject("id")});
                });
        List<UUID> users = jdbc.queryForList("SELECT id FROM users ORDER BY id", UUID.class);
        Timestamp advancedAt = Timestamp.from(now.minus(Duration.ofHours(2)));
        List<Object[]> states = new ArrayList<>();
        for (UUID user : users) {
            List<Object[]> feed = feeds.getOrDefault(user, List.of());
            int unseen = 1 + random.nextInt(MAX_UNSEEN);
            int newSeen = MIN_NEW_SEEN + random.nextInt(MAX_NEW_SEEN - MIN_NEW_SEEN + 1);
            Object[] seen =
                    user.equals(overflow) || unseen >= feed.size() ? null : feed.get(unseen);
            Object[] previous =
                    seen == null || unseen + newSeen >= feed.size()
                            ? null
                            : feed.get(unseen + newSeen);
            states.add(
                    new Object[] {
                        user,
                        seen == null ? null : seen[0],
                        seen == null ? null : seen[1],
                        previous == null ? null : previous[0],
                        previous == null ? null : previous[1],
                        advancedAt
                    });
        }
        jdbc.batchUpdate(
                "INSERT INTO notification_seen_states (user_id, seen_activity_at, seen_id,"
                        + " previous_activity_at, previous_id, advanced_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                states);
        return states.size();
    }

    private static Event event(NotificationDraft draft, Timestamp at) {
        return new Event(draft, at.toInstant(), null);
    }

    /**
     * One notification event.
     *
     * @param read whether the row is read, or null to draw it
     */
    private record Event(NotificationDraft draft, Instant at, Boolean read) {

        // Recipient-scoped, so two recipients' groups on one target never share a key.
        String groupKey() {
            if (!draft.type().isAggregatable() || draft.actorId() == null) {
                return null;
            }
            return draft.recipientId() + "|" + draft.type().aggregationKey(draft.entityId());
        }
    }

    private record Counts(int groups, int singles, int members) {}
}
