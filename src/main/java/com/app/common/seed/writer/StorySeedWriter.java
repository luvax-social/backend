package com.app.common.seed.writer;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.app.common.seed.loader.SeedContent;
import com.app.common.seed.model.ConversationSeed;
import com.app.common.seed.model.MessageSeed;
import com.app.common.seed.model.UserSeed;
import com.app.common.seed.time.SeedTimeline;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Seeds {@code stories} (~{@value #TOTAL_STORY_TARGET}, half still live and half already expired),
 * {@code story_views} and {@code story_likes}.
 *
 * <p>{@code stories.json} does not exist as a source file - {@code users.json} carries no scripted
 * story content, so this writer draws owners itself with a fixed-seed {@link Random}, independent
 * of {@link SeedTimeline}'s own random stream, and reuses a real {@code media_assets} row already
 * owned by the chosen author (from {@link MediaSeedWriter}'s output, read back from the database)
 * rather than minting a new upload - a story does not need its own distinct manifest entry the way
 * a post does.
 *
 * <p><b>{@code view_count}/{@code like_count}</b>: never written - both are trigger-maintained (see
 * {@code story/DATA_RULES.md} Section 2, V49 added the like trigger), so this writer's {@code
 * INSERT} statement omits them entirely.
 *
 * <p><b>Guaranteed story owners</b>: {@link ModerationSeedWriter} resolves several {@code
 * moderation_cases.json} entries' {@code target_story_ref} (a synthetic narrative id, not a real
 * seed reference) by owner username alone - it looks up "any story owned by this account" rather
 * than a specific story identifier. This writer guarantees every username named that way ends up
 * owning at least one story, so that lookup can never come back empty.
 */
@Slf4j
@Service
@Profile("seed & (dev | prod)")
@RequiredArgsConstructor
public class StorySeedWriter {

    private static final long STORY_RANDOM_SEED = 4_217_609L;
    private static final int TOTAL_STORY_TARGET = 120;
    private static final int LIVE_STORY_COUNT = 60;
    private static final int EXPIRED_STORY_COUNT = TOTAL_STORY_TARGET - LIVE_STORY_COUNT;
    private static final Duration STORY_LIFETIME = Duration.ofHours(24);
    private static final int MAX_VIEWERS_PER_STORY = 15;
    private static final int MAX_LIKERS_PER_STORY = 5;

    // These accounts are the target_user of a remove_story/restore_story admin action in
    // moderation_cases.json; ModerationSeedWriter resolves that action's target purely by "an
    // existing story owned by this username", so every one of them must own at least one story.
    private static final List<String> GUARANTEED_STORY_OWNERS =
            List.of(
                    "vivian.frontend",
                    "kingston.strikeout",
                    "cheap.deals.now",
                    "layla.tempban",
                    "toxic.debates");

    private static final String INSERT_STORY_SQL =
            "INSERT INTO stories (id, user_id, media_asset_id, story_type, expires_at, created_at)"
                    + " VALUES (?, ?, ?, ?::story_type, ?, ?)";
    private static final String INSERT_STORY_VIEW_SQL =
            "INSERT INTO story_views (story_id, viewer_id, viewed_at) VALUES (?, ?, ?) ON CONFLICT"
                    + " DO NOTHING";
    private static final String INSERT_STORY_LIKE_SQL =
            "INSERT INTO story_likes (user_id, story_id, created_at) VALUES (?, ?, ?) ON CONFLICT DO"
                    + " NOTHING";

    private final JdbcTemplate jdbc;

    /**
     * Inserts the seeded {@code stories} rows (roughly evenly split between still-live and
     * already-expired) plus their {@code story_views} and {@code story_likes}.
     *
     * @param usersByUsername username-to-id map produced by {@link UserSeedWriter#write}
     */
    public void write(
            SeedContent content, Map<String, UUID> usersByUsername, SeedTimeline timeline) {
        Random random = new Random(STORY_RANDOM_SEED);
        List<UserSeed> users = content.users();

        Map<UUID, List<MediaOwnerAsset>> mediaByOwnerId = fetchMediaAssetsByOwner();
        List<MediaOwnerAsset> anyMediaAsset =
                mediaByOwnerId.values().stream().flatMap(List::stream).toList();
        if (anyMediaAsset.isEmpty()) {
            throw new IllegalStateException(
                    "StorySeedWriter: no media_assets rows found - MediaSeedWriter must run before"
                            + " StorySeedWriter");
        }

        List<String> requiredLiveOwners = liveStoryOwnersRequiredByMessages(content);
        List<String> ownerUsernames = buildOwnerAssignment(users, requiredLiveOwners, random);
        List<Boolean> liveFlags = buildLiveFlags(requiredLiveOwners.size(), random);

        List<Object[]> storyRows = new ArrayList<>();
        List<StoryRow> generated = new ArrayList<>();
        for (int i = 0; i < ownerUsernames.size(); i++) {
            String ownerUsername = ownerUsernames.get(i);
            UUID ownerId = usersByUsername.get(ownerUsername);
            if (ownerId == null) {
                throw new IllegalStateException(
                        "StorySeedWriter: no persisted user found for username '"
                                + ownerUsername
                                + "' - UserSeedWriter must run before StorySeedWriter");
            }
            boolean shouldBeLive = liveFlags.get(i);
            Instant createdAt = timeline.storyCreatedAt(shouldBeLive);
            Instant expiresAt = createdAt.plus(STORY_LIFETIME);

            MediaOwnerAsset media = pickMediaAsset(mediaByOwnerId, anyMediaAsset, ownerId, random);
            UUID storyId = UUID.randomUUID();
            storyRows.add(
                    new Object[] {
                        storyId,
                        ownerId,
                        media.mediaAssetId(),
                        media.storyType(),
                        Timestamp.from(expiresAt),
                        Timestamp.from(createdAt)
                    });
            generated.add(new StoryRow(storyId, ownerId, createdAt));
        }

        jdbc.batchUpdate(INSERT_STORY_SQL, storyRows, storyRows.size(), this::bindStoryRow);
        log.info("[seed] stories: {} rows written", storyRows.size());

        int viewCount = writeStoryViews(generated, usersByUsername, users, random, timeline);
        int likeCount = writeStoryLikes(generated, usersByUsername, users, random, timeline);
        log.info(
                "[seed] story_views: {} rows written, story_likes: {} rows written",
                viewCount,
                likeCount);
    }

    // Every account a conversations.json story_share points at. Derived rather than listed,
    // because a hardcoded copy drifts the moment someone edits a conversation, and the failure it
    // produces surfaces two writers later in MessageSeedWriter rather than here.
    private List<String> liveStoryOwnersRequiredByMessages(SeedContent content) {
        List<String> required = new ArrayList<>();
        for (ConversationSeed conversation : content.conversations()) {
            for (MessageSeed message : conversation.messages()) {
                String owner = message.sharedStoryOwner();
                if (owner != null && !required.contains(owner)) {
                    required.add(owner);
                }
            }
        }
        return required;
    }

    // Builds the 120-entry owner list, front-loaded with the accounts that must own a story at
    // all and the accounts that must own a live one, then filled uniformly at random (with
    // replacement) across every seeded user.
    //
    // The two guaranteed groups are kept at the head rather than shuffled in, because
    // buildLiveFlags assigns liveness by position and the live flags are front-loaded to match.
    // Shuffling either list independently is what left a guaranteed owner holding only an expired
    // story.
    private List<String> buildOwnerAssignment(
            List<UserSeed> users, List<String> requiredLiveOwners, Random random) {
        List<String> owners = new ArrayList<>(requiredLiveOwners);
        for (String owner : GUARANTEED_STORY_OWNERS) {
            if (!owners.contains(owner)) {
                owners.add(owner);
            }
        }
        int guaranteedCount = owners.size();
        List<String> allUsernames = users.stream().map(UserSeed::username).toList();
        List<String> remainder = new ArrayList<>();
        while (guaranteedCount + remainder.size() < TOTAL_STORY_TARGET) {
            remainder.add(allUsernames.get(random.nextInt(allUsernames.size())));
        }
        Collections.shuffle(remainder, random);
        owners.addAll(remainder);
        return owners;
    }

    // Live flags by position: the first requiredLiveCount slots are live so the accounts a
    // story_share names always have one, and the rest of the live quota is shuffled across
    // everything that follows.
    private List<Boolean> buildLiveFlags(int requiredLiveCount, Random random) {
        List<Boolean> tail = new ArrayList<>();
        for (int i = 0; i < LIVE_STORY_COUNT - requiredLiveCount; i++) {
            tail.add(true);
        }
        for (int i = 0; i < EXPIRED_STORY_COUNT; i++) {
            tail.add(false);
        }
        Collections.shuffle(tail, random);

        List<Boolean> flags = new ArrayList<>(TOTAL_STORY_TARGET);
        for (int i = 0; i < requiredLiveCount; i++) {
            flags.add(true);
        }
        flags.addAll(tail);
        return flags;
    }

    private MediaOwnerAsset pickMediaAsset(
            Map<UUID, List<MediaOwnerAsset>> mediaByOwnerId,
            List<MediaOwnerAsset> anyMediaAsset,
            UUID ownerId,
            Random random) {
        List<MediaOwnerAsset> ownMedia = mediaByOwnerId.get(ownerId);
        List<MediaOwnerAsset> pool =
                (ownMedia == null || ownMedia.isEmpty()) ? anyMediaAsset : ownMedia;
        return pool.get(random.nextInt(pool.size()));
    }

    private int writeStoryViews(
            List<StoryRow> stories,
            Map<String, UUID> usersByUsername,
            List<UserSeed> users,
            Random random,
            SeedTimeline timeline) {
        List<Object[]> rows = new ArrayList<>();
        for (StoryRow story : stories) {
            int viewerCount = random.nextInt(MAX_VIEWERS_PER_STORY + 1);
            Set<UUID> viewers = new HashSet<>();
            int attempts = 0;
            while (viewers.size() < viewerCount && attempts < viewerCount * 10 + 20) {
                attempts++;
                UserSeed candidate = users.get(random.nextInt(users.size()));
                UUID viewerId = usersByUsername.get(candidate.username());
                // The story owner viewing their own story never inserts a story_views row
                // (StoryViewServiceImpl.recordView short-circuits before the insert).
                if (viewerId == null
                        || viewerId.equals(story.ownerId())
                        || !viewers.add(viewerId)) {
                    continue;
                }
                Instant viewedAt = timeline.likeOrSaveCreatedAt(story.createdAt());
                rows.add(new Object[] {story.id(), viewerId, Timestamp.from(viewedAt)});
            }
        }
        jdbc.batchUpdate(INSERT_STORY_VIEW_SQL, rows, rows.size(), this::bindReactionRow);
        return rows.size();
    }

    private int writeStoryLikes(
            List<StoryRow> stories,
            Map<String, UUID> usersByUsername,
            List<UserSeed> users,
            Random random,
            SeedTimeline timeline) {
        List<Object[]> rows = new ArrayList<>();
        for (StoryRow story : stories) {
            int likerCount = random.nextInt(MAX_LIKERS_PER_STORY + 1);
            Set<UUID> likers = new HashSet<>();
            int attempts = 0;
            while (likers.size() < likerCount && attempts < likerCount * 10 + 20) {
                attempts++;
                UserSeed candidate = users.get(random.nextInt(users.size()));
                UUID likerId = usersByUsername.get(candidate.username());
                // Self-like is explicitly permitted (story/DATA_RULES.md Section 3B), so likerId is
                // never excluded from matching story.ownerId() here.
                if (likerId == null || !likers.add(likerId)) {
                    continue;
                }
                Instant likedAt = timeline.likeOrSaveCreatedAt(story.createdAt());
                rows.add(new Object[] {likerId, story.id(), Timestamp.from(likedAt)});
            }
        }
        jdbc.batchUpdate(INSERT_STORY_LIKE_SQL, rows, rows.size(), this::bindReactionRow);
        return rows.size();
    }

    private Map<UUID, List<MediaOwnerAsset>> fetchMediaAssetsByOwner() {
        Map<UUID, List<MediaOwnerAsset>> byOwner = new HashMap<>();
        jdbc.query(
                "SELECT id, user_id, media_type FROM media_assets",
                rs -> {
                    UUID id = (UUID) rs.getObject("id");
                    UUID ownerId = (UUID) rs.getObject("user_id");
                    String mediaType = rs.getString("media_type");
                    byOwner.computeIfAbsent(ownerId, key -> new ArrayList<>())
                            .add(new MediaOwnerAsset(id, mediaType));
                });
        return byOwner;
    }

    private void bindStoryRow(PreparedStatement ps, Object[] row) throws SQLException {
        ps.setObject(1, row[0]);
        ps.setObject(2, row[1]);
        ps.setObject(3, row[2]);
        ps.setString(4, (String) row[3]);
        ps.setTimestamp(5, (Timestamp) row[4]);
        ps.setTimestamp(6, (Timestamp) row[5]);
    }

    private void bindReactionRow(PreparedStatement ps, Object[] row) throws SQLException {
        ps.setObject(1, row[0]);
        ps.setObject(2, row[1]);
        ps.setTimestamp(3, (Timestamp) row[2]);
    }

    private record MediaOwnerAsset(UUID mediaAssetId, String storyType) {}

    private record StoryRow(UUID id, UUID ownerId, Instant createdAt) {}
}
