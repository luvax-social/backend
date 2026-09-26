package com.app.common.seed.writer;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
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
import com.app.common.seed.model.UserSeed;
import com.app.common.seed.time.SeedTimeline;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Seeds the follow graph ({@code follows}) and block list ({@code blocks}) across the 90 accounts
 * from {@code users.json}.
 *
 * <p>Neither table has a source JSON file - {@code users.json} carries only each account's {@code
 * is_private} flag, not a scripted social graph - so this writer generates the topology itself with
 * a fixed-seed {@link Random}, independent of {@link SeedTimeline}'s own random stream, so
 * re-running the writer against the same seed content reproduces the same graph.
 *
 * <p>Selection is weighted rather than uniform. Uniform sampling produced a graph with no hubs, no
 * topical clustering and a follower count that was almost the same for every account, which is why
 * {@code seed/README.md} used to warn that no meaning should be read into who follows whom. Four
 * weights shape it instead:
 *
 * <ul>
 *   <li><b>Homophily.</b> Each persona belongs to one or more affinity clusters. A candidate
 *       sharing a cluster with the follower is weighted {@value #SAME_CLUSTER_WEIGHT} times the
 *       base, an adjacent cluster {@value #ADJACENT_CLUSTER_WEIGHT} times, anything else once.
 *   <li><b>Preferential attachment.</b> Weight scales with the target's in-degree so far, so a few
 *       accounts accumulate genuine hub status instead of the flat distribution uniform sampling
 *       gives.
 *   <li><b>Reciprocity.</b> A follow inside a shared cluster is followed back about {@value
 *       #RECIPROCITY_PERCENT}% of the time, and never by a hub, which is what keeps hubs from also
 *       having enormous following counts.
 *   <li><b>Consumer asymmetry.</b> The accounts that never post follow far more than average and
 *       are followed back by nobody, which is the shape of an account that only reads.
 * </ul>
 *
 * <p>Three QA-account behaviours documented in {@code users.json}'s {@code qa_note} fields are
 * guaranteed by construction rather than left to chance: {@code user_new_empty} is excluded from
 * both sides of every follow assignment this writer makes, so it neither receives a follow ("zero
 * followers" per its note) nor is ever picked as a follower of anyone else ("zero following" per
 * its note) - the isolated node the cold-start recommendation case needs, where both the account's
 * own following feed and its inbound audience are empty. {@code user_private} always receives a
 * minimum number of inbound pending requests ("pending follow requests inbound" per its note), and
 * {@code user_power} always receives a large explicit batch of accepted followers ("high follower
 * count" per its note).
 */
@Slf4j
@Service
@Profile("seed & (dev | prod)")
@RequiredArgsConstructor
public class SocialGraphSeedWriter {

    private static final long GRAPH_RANDOM_SEED = 8_690_251L;
    private static final int TOTAL_FOLLOWS_TARGET = 5500;
    private static final int MAX_PENDING_TOWARD_PRIVATE = 40;
    private static final int TOTAL_BLOCKS_TARGET = 25;
    private static final int MIN_FOLLOWING_PER_USER = 12;
    private static final int MAX_FOLLOWING_PER_USER = 45;
    private static final int MIN_FOLLOWING_PER_CONSUMER = 40;
    private static final int MAX_FOLLOWING_PER_CONSUMER = 120;

    private static final double SAME_CLUSTER_WEIGHT = 4.0;
    private static final double ADJACENT_CLUSTER_WEIGHT = 2.0;
    private static final double UNRELATED_WEIGHT = 1.0;
    // A consumer publishes nothing, so following one is following an empty feed. Excluding them
    // outright gave all 47 exactly zero followers, which is a visible artefact rather than the
    // asymmetry this is meant to model - a real account that only reads still has a few friends.
    private static final double CONSUMER_TARGET_WEIGHT = 0.12;
    // Sub-linear, so popularity compounds without one account swallowing the whole graph.
    private static final double POPULARITY_EXPONENT = 0.75;
    private static final int RECIPROCITY_PERCENT = 35;
    // An account past this many followers stops following people back. Without it the hubs
    // preferential attachment creates end up with symmetric following counts, which is the flat
    // distribution this model exists to avoid.
    private static final int HUB_FOLLOWER_THRESHOLD = 45;

    // Affinity clusters by persona. A persona in two clusters bridges them, which is what keeps
    // the graph connected rather than splitting it into disjoint communities.
    private static final Map<String, Set<String>> CLUSTERS_BY_PERSONA =
            Map.ofEntries(
                    Map.entry("p01_dev_backend", Set.of("tech")),
                    Map.entry("p02_designer_product", Set.of("tech", "creative")),
                    Map.entry("p03_photographer_freelance", Set.of("creative")),
                    Map.entry("p04_student_university", Set.of("life")),
                    Map.entry("p05_shop_owner_clothes", Set.of("commerce")),
                    Map.entry("p06_gym_runner", Set.of("fitness")),
                    Map.entry("p07_fnb_owner", Set.of("commerce")),
                    Map.entry("p08_finance_office", Set.of("life")),
                    Map.entry("p09_lurker_commenter", Set.of()),
                    Map.entry("p10_spam_scam", Set.of()),
                    Map.entry("p11_silent_consumer", Set.of()),
                    Map.entry("p12_musician", Set.of("creative", "media")),
                    Map.entry("p13_filmmaker", Set.of("creative", "media")),
                    Map.entry("p14_streamer", Set.of("media", "tech")));

    // Clusters that are not the same but whose audiences overlap.
    private static final Set<String> ADJACENT_CLUSTER_PAIRS =
            Set.of(
                    "creative|tech",
                    "creative|media",
                    "commerce|life",
                    "fitness|life",
                    "media|tech");

    // Personas that never post. They receive an interest cluster anyway, drawn deterministically,
    // so that what they follow is coherent rather than arbitrary - a reader still reads about
    // something in particular.
    private static final Set<String> CONSUMER_PERSONAS =
            Set.of("p09_lurker_commenter", "p11_silent_consumer");

    private static final List<String> INTEREST_POOL =
            List.of("tech", "creative", "commerce", "fitness", "life", "media");

    private static final String EMPTY_SOCIAL_GRAPH_USERNAME = "user_new_empty";
    private static final String GUARANTEED_PENDING_TARGET_USERNAME = "user_private";
    private static final int GUARANTEED_PENDING_COUNT = 6;
    private static final String BOOSTED_FOLLOWER_USERNAME = "user_power";
    private static final int BOOSTED_FOLLOWER_COUNT = 55;

    private static final String INSERT_FOLLOW_SQL =
            "INSERT INTO follows (follower_id, following_id, status, created_at) VALUES (?, ?,"
                    + " ?::follow_status, ?)";
    private static final String INSERT_BLOCK_SQL =
            "INSERT INTO blocks (blocker_id, blocked_id, created_at) VALUES (?, ?, ?)";

    private final JdbcTemplate jdbc;

    /**
     * Inserts the seeded {@code follows} (~{@value #TOTAL_FOLLOWS_TARGET} rows, with up to {@value
     * #MAX_PENDING_TOWARD_PRIVATE} left {@code pending} toward private accounts) and {@code blocks}
     * (~{@value #TOTAL_BLOCKS_TARGET} pairs) rows.
     *
     * <p>Never writes {@code users.follower_count}/{@code following_count} - both are
     * trigger-maintained from {@code follows}, and a {@code pending} row correctly leaves them
     * unchanged (see {@code docs/modules/social/DATA_RULES.md}).
     *
     * @param usersByUsername username-to-id map produced by {@link UserSeedWriter#write}
     */
    public void write(
            SeedContent content, Map<String, UUID> usersByUsername, SeedTimeline timeline) {
        List<UserSeed> users = content.users();
        Map<String, Boolean> isPrivateByUsername = new HashMap<>();
        for (UserSeed user : users) {
            isPrivateByUsername.put(user.username(), user.isPrivate());
        }
        Map<UUID, Instant> createdAtByUserId = fetchCreatedAtByUserId();

        Random random = new Random(GRAPH_RANDOM_SEED);
        List<Object[]> blockRows = new ArrayList<>();
        Set<String> blockedUnorderedPairs = new HashSet<>();
        Set<String> blockedDirectedPairs = new HashSet<>();
        generateBlocks(
                users,
                usersByUsername,
                createdAtByUserId,
                timeline,
                random,
                blockRows,
                blockedUnorderedPairs,
                blockedDirectedPairs);

        List<Object[]> followRows = new ArrayList<>();
        Set<String> followPairs = new HashSet<>();
        int[] pendingCount = {0};

        addGuaranteedPendingFollows(
                users,
                usersByUsername,
                createdAtByUserId,
                timeline,
                random,
                followRows,
                followPairs,
                blockedDirectedPairs,
                pendingCount);
        addBoostedFollowers(
                users,
                usersByUsername,
                createdAtByUserId,
                timeline,
                random,
                followRows,
                followPairs,
                blockedDirectedPairs);
        Map<String, Set<String>> clustersByUsername = assignClusters(users, random);
        Map<String, Integer> followerCounts = new HashMap<>();
        for (Object[] row : followRows) {
            // The guaranteed batches above already gave user_power and user_private their
            // inbound edges; preferential attachment must see them.
            UUID followingId = (UUID) row[1];
            for (Map.Entry<String, UUID> entry : usersByUsername.entrySet()) {
                if (entry.getValue().equals(followingId)) {
                    followerCounts.merge(entry.getKey(), 1, Integer::sum);
                    break;
                }
            }
        }

        fillWeightedFollows(
                users,
                usersByUsername,
                isPrivateByUsername,
                clustersByUsername,
                followerCounts,
                createdAtByUserId,
                timeline,
                random,
                followRows,
                followPairs,
                blockedDirectedPairs,
                pendingCount);

        jdbc.batchUpdate(INSERT_FOLLOW_SQL, followRows, followRows.size(), this::bindFollowRow);
        jdbc.batchUpdate(INSERT_BLOCK_SQL, blockRows, blockRows.size(), this::bindBlockRow);

        log.info(
                "[seed] follows: {} rows written ({} pending), blocks: {} rows written",
                followRows.size(),
                pendingCount[0],
                blockRows.size());
    }

    // Read back from the DB rather than recomputing via SeedTimeline.userCreatedAt(UserSeed):
    // that method draws from SeedTimeline's shared Random stream, so calling it a second time
    // here would advance the stream and return a value different from what UserSeedWriter already
    // persisted. Querying the row UserSeedWriter wrote is the only way to get the true value.
    private Map<UUID, Instant> fetchCreatedAtByUserId() {
        Map<UUID, Instant> createdAtByUserId = new HashMap<>();
        jdbc.query(
                "SELECT id, created_at FROM users",
                rs -> {
                    UUID id = (UUID) rs.getObject("id");
                    Instant createdAt = rs.getTimestamp("created_at").toInstant();
                    createdAtByUserId.put(id, createdAt);
                });
        return createdAtByUserId;
    }

    private void generateBlocks(
            List<UserSeed> users,
            Map<String, UUID> usersByUsername,
            Map<UUID, Instant> createdAtByUserId,
            SeedTimeline timeline,
            Random random,
            List<Object[]> blockRows,
            Set<String> blockedUnorderedPairs,
            Set<String> blockedDirectedPairs) {
        int attempts = 0;
        int maxAttempts = TOTAL_BLOCKS_TARGET * 50;
        while (blockRows.size() < TOTAL_BLOCKS_TARGET && attempts < maxAttempts) {
            attempts++;
            UserSeed blocker = users.get(random.nextInt(users.size()));
            UserSeed blocked = users.get(random.nextInt(users.size()));
            if (blocker.username().equals(blocked.username())) {
                continue;
            }
            String unorderedKey = unorderedPairKey(blocker.username(), blocked.username());
            if (!blockedUnorderedPairs.add(unorderedKey)) {
                continue;
            }
            UUID blockerId = usersByUsername.get(blocker.username());
            UUID blockedId = usersByUsername.get(blocked.username());
            Instant createdAt =
                    timeline.followCreatedAt(
                            createdAtByUserId.get(blockerId), createdAtByUserId.get(blockedId));
            blockRows.add(new Object[] {blockerId, blockedId, Timestamp.from(createdAt)});
            blockedDirectedPairs.add(blocker.username() + "->" + blocked.username());
            blockedDirectedPairs.add(blocked.username() + "->" + blocker.username());
        }
    }

    // Directly satisfies user_private's qa_note ("pending follow requests inbound") without relying
    // on the general random fill below to happen to land enough pending edges on this one account.
    private void addGuaranteedPendingFollows(
            List<UserSeed> users,
            Map<String, UUID> usersByUsername,
            Map<UUID, Instant> createdAtByUserId,
            SeedTimeline timeline,
            Random random,
            List<Object[]> followRows,
            Set<String> followPairs,
            Set<String> blockedDirectedPairs,
            int[] pendingCount) {
        List<UserSeed> candidates = new ArrayList<>(users);
        Collections.shuffle(candidates, random);
        int added = 0;
        for (UserSeed follower : candidates) {
            if (added >= GUARANTEED_PENDING_COUNT) {
                break;
            }
            if (follower.username().equals(GUARANTEED_PENDING_TARGET_USERNAME)) {
                continue;
            }
            if (follower.username().equals(EMPTY_SOCIAL_GRAPH_USERNAME)) {
                continue;
            }
            if (blockedDirectedPairs.contains(
                    follower.username() + "->" + GUARANTEED_PENDING_TARGET_USERNAME)) {
                continue;
            }
            addFollow(
                    follower.username(),
                    GUARANTEED_PENDING_TARGET_USERNAME,
                    "pending",
                    usersByUsername,
                    createdAtByUserId,
                    timeline,
                    followRows,
                    followPairs);
            pendingCount[0]++;
            added++;
        }
    }

    // Directly satisfies user_power's qa_note ("high follower count") without relying on uniform
    // random sampling to happen to favor this one account.
    private void addBoostedFollowers(
            List<UserSeed> users,
            Map<String, UUID> usersByUsername,
            Map<UUID, Instant> createdAtByUserId,
            SeedTimeline timeline,
            Random random,
            List<Object[]> followRows,
            Set<String> followPairs,
            Set<String> blockedDirectedPairs) {
        List<UserSeed> candidates = new ArrayList<>(users);
        Collections.shuffle(candidates, random);
        int added = 0;
        for (UserSeed follower : candidates) {
            if (added >= BOOSTED_FOLLOWER_COUNT) {
                break;
            }
            if (follower.username().equals(BOOSTED_FOLLOWER_USERNAME)) {
                continue;
            }
            if (follower.username().equals(EMPTY_SOCIAL_GRAPH_USERNAME)) {
                continue;
            }
            if (blockedDirectedPairs.contains(
                    follower.username() + "->" + BOOSTED_FOLLOWER_USERNAME)) {
                continue;
            }
            addFollow(
                    follower.username(),
                    BOOSTED_FOLLOWER_USERNAME,
                    "accepted",
                    usersByUsername,
                    createdAtByUserId,
                    timeline,
                    followRows,
                    followPairs);
            added++;
        }
    }

    private Map<String, Set<String>> assignClusters(List<UserSeed> users, Random random) {
        Map<String, Set<String>> clusters = new HashMap<>();
        for (UserSeed user : users) {
            Set<String> declared = CLUSTERS_BY_PERSONA.getOrDefault(user.personaId(), Set.of());
            if (!declared.isEmpty()) {
                clusters.put(user.username(), declared);
                continue;
            }
            if (CONSUMER_PERSONAS.contains(user.personaId())) {
                clusters.put(
                        user.username(),
                        Set.of(INTEREST_POOL.get(random.nextInt(INTEREST_POOL.size()))));
            } else {
                // p10_spam_scam and anything else with no declared cluster follows broadly and
                // indiscriminately, which is what the persona describes.
                clusters.put(user.username(), Set.of());
            }
        }
        return clusters;
    }

    private double affinity(Set<String> followerClusters, Set<String> candidateClusters) {
        if (followerClusters.isEmpty() || candidateClusters.isEmpty()) {
            return UNRELATED_WEIGHT;
        }
        for (String followerCluster : followerClusters) {
            if (candidateClusters.contains(followerCluster)) {
                return SAME_CLUSTER_WEIGHT;
            }
        }
        for (String followerCluster : followerClusters) {
            for (String candidateCluster : candidateClusters) {
                if (ADJACENT_CLUSTER_PAIRS.contains(pairKey(followerCluster, candidateCluster))) {
                    return ADJACENT_CLUSTER_WEIGHT;
                }
            }
        }
        return UNRELATED_WEIGHT;
    }

    private String pairKey(String a, String b) {
        return a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a;
    }

    private boolean isConsumer(UserSeed user) {
        return CONSUMER_PERSONAS.contains(user.personaId());
    }

    private void fillWeightedFollows(
            List<UserSeed> users,
            Map<String, UUID> usersByUsername,
            Map<String, Boolean> isPrivateByUsername,
            Map<String, Set<String>> clustersByUsername,
            Map<String, Integer> followerCounts,
            Map<UUID, Instant> createdAtByUserId,
            SeedTimeline timeline,
            Random random,
            List<Object[]> followRows,
            Set<String> followPairs,
            Set<String> blockedDirectedPairs,
            int[] pendingCount) {
        List<UserSeed> followerOrder = new ArrayList<>(users);
        Collections.shuffle(followerOrder, random);

        Map<String, UserSeed> userByUsername = new HashMap<>();
        for (UserSeed user : users) {
            userByUsername.put(user.username(), user);
        }

        List<String[]> reciprocityCandidates = new ArrayList<>();

        for (UserSeed follower : followerOrder) {
            if (followRows.size() >= TOTAL_FOLLOWS_TARGET) {
                break;
            }
            if (follower.username().equals(EMPTY_SOCIAL_GRAPH_USERNAME)) {
                continue;
            }
            boolean consumer = isConsumer(follower);
            int targetFollowingCount =
                    consumer
                            ? MIN_FOLLOWING_PER_CONSUMER
                                    + random.nextInt(
                                            MAX_FOLLOWING_PER_CONSUMER
                                                    - MIN_FOLLOWING_PER_CONSUMER
                                                    + 1)
                            : MIN_FOLLOWING_PER_USER
                                    + random.nextInt(
                                            MAX_FOLLOWING_PER_USER - MIN_FOLLOWING_PER_USER + 1);

            Set<String> followerClusters =
                    clustersByUsername.getOrDefault(follower.username(), Set.of());

            int addedForThisFollower = 0;
            int attempts = 0;
            int maxAttempts = targetFollowingCount * 12;
            while (addedForThisFollower < targetFollowingCount
                    && followRows.size() < TOTAL_FOLLOWS_TARGET
                    && attempts < maxAttempts) {
                attempts++;
                UserSeed candidate =
                        pickWeightedCandidate(
                                users,
                                follower,
                                followerClusters,
                                clustersByUsername,
                                followerCounts,
                                followPairs,
                                blockedDirectedPairs,
                                random);
                if (candidate == null) {
                    break;
                }
                boolean candidateIsPrivate =
                        Boolean.TRUE.equals(isPrivateByUsername.get(candidate.username()));
                if (candidateIsPrivate && pendingCount[0] >= MAX_PENDING_TOWARD_PRIVATE) {
                    continue;
                }
                String status = candidateIsPrivate ? "pending" : "accepted";
                addFollow(
                        follower.username(),
                        candidate.username(),
                        status,
                        usersByUsername,
                        createdAtByUserId,
                        timeline,
                        followRows,
                        followPairs);
                if (candidateIsPrivate) {
                    pendingCount[0]++;
                } else {
                    followerCounts.merge(candidate.username(), 1, Integer::sum);
                }
                addedForThisFollower++;

                // A consumer is never followed back; that asymmetry is the whole point of the
                // persona. Reciprocity is otherwise considered only inside a shared cluster.
                if (!consumer
                        && "accepted".equals(status)
                        && affinity(
                                        followerClusters,
                                        clustersByUsername.getOrDefault(
                                                candidate.username(), Set.of()))
                                == SAME_CLUSTER_WEIGHT) {
                    reciprocityCandidates.add(
                            new String[] {candidate.username(), follower.username()});
                }
            }
        }

        applyReciprocity(
                reciprocityCandidates,
                userByUsername,
                usersByUsername,
                isPrivateByUsername,
                followerCounts,
                createdAtByUserId,
                timeline,
                random,
                followRows,
                followPairs,
                blockedDirectedPairs);
    }

    private UserSeed pickWeightedCandidate(
            List<UserSeed> users,
            UserSeed follower,
            Set<String> followerClusters,
            Map<String, Set<String>> clustersByUsername,
            Map<String, Integer> followerCounts,
            Set<String> followPairs,
            Set<String> blockedDirectedPairs,
            Random random) {
        double totalWeight = 0.0;
        List<UserSeed> eligible = new ArrayList<>();
        List<Double> weights = new ArrayList<>();
        for (UserSeed candidate : users) {
            if (candidate.username().equals(follower.username())
                    || candidate.username().equals(EMPTY_SOCIAL_GRAPH_USERNAME)
                    || followPairs.contains(follower.username() + "->" + candidate.username())
                    || blockedDirectedPairs.contains(
                            follower.username() + "->" + candidate.username())) {
                continue;
            }
            double weight =
                    affinity(
                            followerClusters,
                            clustersByUsername.getOrDefault(candidate.username(), Set.of()));
            if (isConsumer(candidate)) {
                weight *= CONSUMER_TARGET_WEIGHT;
            }
            weight *=
                    Math.pow(
                            1.0 + followerCounts.getOrDefault(candidate.username(), 0),
                            POPULARITY_EXPONENT);
            eligible.add(candidate);
            weights.add(weight);
            totalWeight += weight;
        }
        if (eligible.isEmpty() || totalWeight <= 0.0) {
            return null;
        }
        double roll = random.nextDouble() * totalWeight;
        double running = 0.0;
        for (int i = 0; i < eligible.size(); i++) {
            running += weights.get(i);
            if (roll <= running) {
                return eligible.get(i);
            }
        }
        return eligible.get(eligible.size() - 1);
    }

    private void applyReciprocity(
            List<String[]> candidates,
            Map<String, UserSeed> userByUsername,
            Map<String, UUID> usersByUsername,
            Map<String, Boolean> isPrivateByUsername,
            Map<String, Integer> followerCounts,
            Map<UUID, Instant> createdAtByUserId,
            SeedTimeline timeline,
            Random random,
            List<Object[]> followRows,
            Set<String> followPairs,
            Set<String> blockedDirectedPairs) {
        for (String[] pair : candidates) {
            String followerUsername = pair[0];
            String targetUsername = pair[1];
            if (random.nextInt(100) >= RECIPROCITY_PERCENT) {
                continue;
            }
            if (followerCounts.getOrDefault(followerUsername, 0) >= HUB_FOLLOWER_THRESHOLD) {
                continue;
            }
            if (followPairs.contains(followerUsername + "->" + targetUsername)
                    || blockedDirectedPairs.contains(followerUsername + "->" + targetUsername)) {
                continue;
            }
            UserSeed target = userByUsername.get(targetUsername);
            if (target == null || Boolean.TRUE.equals(isPrivateByUsername.get(targetUsername))) {
                continue;
            }
            addFollow(
                    followerUsername,
                    targetUsername,
                    "accepted",
                    usersByUsername,
                    createdAtByUserId,
                    timeline,
                    followRows,
                    followPairs);
            followerCounts.merge(targetUsername, 1, Integer::sum);
        }
    }

    private void addFollow(
            String followerUsername,
            String followingUsername,
            String status,
            Map<String, UUID> usersByUsername,
            Map<UUID, Instant> createdAtByUserId,
            SeedTimeline timeline,
            List<Object[]> followRows,
            Set<String> followPairs) {
        String pairKey = followerUsername + "->" + followingUsername;
        if (!followPairs.add(pairKey)) {
            return;
        }
        UUID followerId = usersByUsername.get(followerUsername);
        UUID followingId = usersByUsername.get(followingUsername);
        Instant createdAt =
                timeline.followCreatedAt(
                        createdAtByUserId.get(followerId), createdAtByUserId.get(followingId));
        followRows.add(new Object[] {followerId, followingId, status, Timestamp.from(createdAt)});
    }

    private String unorderedPairKey(String usernameA, String usernameB) {
        return usernameA.compareTo(usernameB) < 0
                ? usernameA + "|" + usernameB
                : usernameB + "|" + usernameA;
    }

    private void bindFollowRow(PreparedStatement ps, Object[] row) throws SQLException {
        ps.setObject(1, row[0]);
        ps.setObject(2, row[1]);
        ps.setString(3, (String) row[2]);
        ps.setTimestamp(4, (Timestamp) row[3]);
    }

    private void bindBlockRow(PreparedStatement ps, Object[] row) throws SQLException {
        ps.setObject(1, row[0]);
        ps.setObject(2, row[1]);
        ps.setTimestamp(3, (Timestamp) row[2]);
    }
}
