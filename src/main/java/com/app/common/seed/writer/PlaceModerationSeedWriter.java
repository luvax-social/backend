package com.app.common.seed.writer;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.app.common.seed.loader.SeedContent;
import com.app.common.seed.model.PlaceSeed;
import com.app.common.seed.time.SeedTimeline;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Seeds the moderation history of the places registry: the audit rows for every ban, restoration
 * and deletion, and the reports people filed against place names.
 *
 * <p>Runs after {@link ModerationSeedWriter} because it needs administrators in place, and after
 * {@link PostCreationSeedWriter}, which writes the places themselves in the state they hold at the
 * end of the run. This writer adds the history that explains that state: a banned place carries a
 * {@code ban_place} row, a restored place a {@code ban_place} followed by an {@code unban_place}, a
 * deleted place a {@code delete_place}. The decision time recorded on the place is moved to the
 * time of the last audit row, so the registry and the audit log agree.
 *
 * <p>Reports against a place that was banned or removed are resolved and linked from the audit row
 * that closed them, the way the administrative panel links them. Reports against places that stay
 * active are left pending, in review or dismissed.
 */
@Slf4j
@Service
@Profile("seed & (dev | prod)")
@RequiredArgsConstructor
public class PlaceModerationSeedWriter {

    private static final long RANDOM_SEED = 4_417_902L;

    private static final List<String> REPORT_REASONS =
            List.of("spam", "scam", "other", "hate_speech", "violence", "nudity");

    private static final String INSERT_REPORT_SQL =
            "INSERT INTO reports (id, reporter_id, report_type, report_reason, entity_id, status,"
                    + " reviewed_by, reviewed_at, resolution_note, created_at) VALUES (?, ?,"
                    + " 'place'::report_type, ?::report_reason, ?, ?::report_status, ?, ?, ?, ?)";

    private static final String INSERT_ACTION_SQL =
            "INSERT INTO admin_actions (id, admin_id, action_type, target_entity_type,"
                    + " target_entity_id, report_id, reason, metadata, created_at) VALUES (?, ?,"
                    + " ?::admin_action_type, 'place', ?, ?, ?, ?::jsonb, ?)";

    private final JdbcTemplate jdbc;

    /**
     * Writes the audit rows and the place reports.
     *
     * @param content the loaded seed content, whose places say which history each one has
     * @param timeline the shared timeline; only its reference instant is used
     */
    public void write(SeedContent content, SeedTimeline timeline) {
        Random random = new Random(RANDOM_SEED);
        Instant now = timeline.referenceNow();
        List<UUID> admins =
                jdbc.queryForList(
                        "SELECT id FROM users WHERE role = 'admin' AND status = 'active' ORDER BY"
                                + " id LIMIT 1",
                        UUID.class);
        if (admins.isEmpty()) {
            log.warn("[seed] place moderation skipped: no active administrator exists");
            return;
        }
        UUID adminId = admins.get(0);
        List<UUID> reporters =
                jdbc.queryForList(
                        "SELECT id FROM users WHERE role = 'user' AND status = 'active' AND"
                                + " deleted_at IS NULL ORDER BY id",
                        UUID.class);

        List<PlaceRow> places = loadPlaces();
        List<Object[]> reports = new ArrayList<>();
        List<Object[]> actions = new ArrayList<>();
        List<Object[]> decisions = new ArrayList<>();
        int reporterCursor = 0;
        int resolvedReports = 0;

        for (PlaceSeed seed : content.places()) {
            PlaceRow place =
                    places.stream()
                            .filter(p -> p.name().equals(seed.name()))
                            .findFirst()
                            .orElse(null);
            if (place == null || "none".equals(seed.history()) && "active".equals(seed.status())) {
                continue;
            }
            Instant banAt =
                    earlier(place.createdAt().plus(Duration.ofDays(5 + random.nextInt(20))), now);
            String note = seed.statusNote();
            boolean deleted = "deleted".equals(seed.status());
            boolean restored = "unbanned".equals(seed.history());

            UUID reportId = null;
            if (!deleted) {
                // A place reported by a person and then banned; the report is closed by the ban.
                reportId = UUID.randomUUID();
                UUID reporter = reporters.get(reporterCursor++ % reporters.size());
                Instant reportedAt = banAt.minus(Duration.ofHours(2 + random.nextInt(40)));
                reports.add(
                        new Object[] {
                            reportId,
                            reporter,
                            REPORT_REASONS.get(random.nextInt(REPORT_REASONS.size())),
                            place.id(),
                            "resolved",
                            adminId,
                            Timestamp.from(banAt),
                            note,
                            Timestamp.from(reportedAt)
                        });
                resolvedReports++;
            }

            String closing = deleted ? "delete_place" : "ban_place";
            actions.add(
                    new Object[] {
                        UUID.randomUUID(),
                        adminId,
                        closing,
                        place.id(),
                        reportId,
                        note,
                        metadata(place, deleted ? "deleted" : "banned", "active"),
                        Timestamp.from(banAt)
                    });
            Instant decidedAt = banAt;
            if (restored) {
                decidedAt = earlier(banAt.plus(Duration.ofDays(2 + random.nextInt(8))), now);
                actions.add(
                        new Object[] {
                            UUID.randomUUID(),
                            adminId,
                            "unban_place",
                            place.id(),
                            null,
                            null,
                            metadata(place, "active", "banned"),
                            Timestamp.from(decidedAt)
                        });
            }
            decisions.add(new Object[] {Timestamp.from(decidedAt), adminId, place.id()});
        }

        // Reports against places that stay active: some still waiting, some looked at, some
        // dismissed because the name was fine.
        List<PlaceRow> active =
                places.stream()
                        .filter(p -> "active".equals(p.status()) && !wasTouched(content, p))
                        .toList();
        String[] openStates = {
            "pending", "pending", "pending", "reviewing", "dismissed", "dismissed"
        };
        for (int i = 0; i < openStates.length && i < active.size(); i++) {
            PlaceRow place = active.get(i);
            String state = openStates[i];
            Instant reportedAt =
                    earlier(place.createdAt().plus(Duration.ofDays(20 + random.nextInt(30))), now);
            boolean decided = !"pending".equals(state);
            reports.add(
                    new Object[] {
                        UUID.randomUUID(),
                        reporters.get(reporterCursor++ % reporters.size()),
                        REPORT_REASONS.get(random.nextInt(REPORT_REASONS.size())),
                        place.id(),
                        state,
                        decided ? adminId : null,
                        decided
                                ? Timestamp.from(earlier(reportedAt.plus(Duration.ofHours(6)), now))
                                : null,
                        "dismissed".equals(state) ? "The name is an ordinary place" : null,
                        Timestamp.from(reportedAt)
                    });
        }

        jdbc.batchUpdate(INSERT_REPORT_SQL, reports);
        jdbc.batchUpdate(INSERT_ACTION_SQL, actions);
        jdbc.batchUpdate("UPDATE places SET status_at = ?, status_by = ? WHERE id = ?", decisions);
        log.info(
                "[seed] place moderation: {} audit rows, {} reports ({} resolved by an action)",
                actions.size(),
                reports.size(),
                resolvedReports);
    }

    private record PlaceRow(
            UUID id, String name, String status, int postCount, Instant createdAt) {}

    private List<PlaceRow> loadPlaces() {
        List<PlaceRow> places = new ArrayList<>();
        jdbc.query(
                "SELECT id, name, status::text AS status, post_count, created_at FROM places ORDER"
                        + " BY name",
                rs -> {
                    places.add(
                            new PlaceRow(
                                    (UUID) rs.getObject("id"),
                                    rs.getString("name"),
                                    rs.getString("status"),
                                    rs.getInt("post_count"),
                                    rs.getTimestamp("created_at").toInstant()));
                });
        return places;
    }

    private static boolean wasTouched(SeedContent content, PlaceRow place) {
        return content.places().stream()
                .anyMatch(
                        seed -> seed.name().equals(place.name()) && !"none".equals(seed.history()));
    }

    private static String metadata(PlaceRow place, String resulting, String previous) {
        Map<String, Object> values =
                Map.of(
                        "name",
                        place.name(),
                        "resultingStatus",
                        resulting,
                        "previousStatus",
                        previous,
                        "affectedPosts",
                        place.postCount());
        return new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(values).toString();
    }

    private static Instant earlier(Instant candidate, Instant limit) {
        return candidate.isAfter(limit) ? limit : candidate;
    }
}
