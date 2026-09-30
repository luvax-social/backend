package com.app.modules.recommendation.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.clickhouse.ClickHouseContainer;

import com.app.modules.recommendation.enums.UserEventType;
import com.app.testsupport.ClickHouseTestSupport;

/**
 * The row-loss test for the activity log's keyset: events that share one recording time are paged
 * so the page size cuts into the tie group, and every event must come back exactly once.
 *
 * <p>The identifiers are chosen so that ClickHouse's native UUID order (second half first) and the
 * text order the listing uses disagree. A listing that ordered or compared by the native identifier
 * would drop and repeat rows the moment a page boundary fell inside the tie group; the text order
 * is also the order every other cursor in the application uses.
 */
class UserEventKeysetRowLossIT {

    private static final List<UUID> TIED_IDS =
            List.of(
                    UUID.fromString("00000000-0000-0000-ffff-000000000000"),
                    UUID.fromString("ffffffff-0000-0000-0000-000000000000"),
                    UUID.fromString("11111111-2222-3333-4444-555555555555"),
                    UUID.fromString("88888888-0000-0000-0000-000000000001"),
                    UUID.fromString("00000000-0000-0000-0000-000000000009"));

    // Microsecond precision on purpose: the cursor carries microseconds, and a comparison that
    // truncated them would split the tie group.
    private static final OffsetDateTime SHARED_INSTANT =
            OffsetDateTime.of(2026, 1, 1, 12, 0, 0, 123_456_000, ZoneOffset.UTC);
    private static final OffsetDateTime FROM = SHARED_INSTANT.minusDays(1);
    private static final OffsetDateTime TO = SHARED_INSTANT.plusDays(1);
    private static final int PAGE_SIZE = 2;

    static ClickHouseContainer clickhouse;
    static UserEventAnalyticsRepository repository;

    @BeforeAll
    static void start() {
        clickhouse = ClickHouseTestSupport.startProvisioned();
        ClickHouseTestSupport.applyMigrations(clickhouse);
        repository =
                new UserEventAnalyticsRepositoryImpl(
                        ClickHouseTestSupport.directOperations(clickhouse));
    }

    @AfterAll
    static void stop() {
        clickhouse.stop();
    }

    @Test
    void tieGroupOnCreatedAt_pagesEveryEventExactlyOnceInTextOrder() {
        UUID user = UUID.randomUUID();
        for (UUID id : TIED_IDS) {
            repository.insertImported(
                    id, user, UserEventType.SESSION_START, null, null, "", SHARED_INSTANT);
        }

        List<UUID> seen = new ArrayList<>();
        OffsetDateTime cursorAt = null;
        UUID cursorId = null;
        int pages = 0;
        while (true) {
            List<UserEventRow> page =
                    repository.findPage(user, FROM, TO, null, cursorAt, cursorId, PAGE_SIZE);
            page.forEach(row -> seen.add(row.id()));
            pages++;
            if (page.size() < PAGE_SIZE) {
                break;
            }
            UserEventRow last = page.get(page.size() - 1);
            cursorAt = last.createdAt();
            cursorId = last.id();
        }

        assertThat(seen).containsExactlyElementsOf(expectedOrder());
        assertThat(seen).doesNotHaveDuplicates();
        assertThat(pages).isEqualTo(3);
    }

    @Test
    void theTextOrderIsNotClickHousesNativeOrder_soTheTestCanFailAListingThatUsesIt() {
        UUID first = TIED_IDS.get(0);
        UUID second = TIED_IDS.get(1);

        // In text order the second sorts after the first; ClickHouse's native order puts them the
        // other way round, which is what makes the tie group above a real regression test.
        assertThat(second.toString().compareTo(first.toString())).isPositive();
        assertThat(nativeClickHouseCompare(first, second)).isPositive();
    }

    private static int nativeClickHouseCompare(UUID a, UUID b) {
        // ClickHouse compares the second 8 bytes of a UUID first, as unsigned.
        int byLow = Long.compareUnsigned(a.getLeastSignificantBits(), b.getLeastSignificantBits());
        return byLow != 0
                ? byLow
                : Long.compareUnsigned(a.getMostSignificantBits(), b.getMostSignificantBits());
    }

    private static List<UUID> expectedOrder() {
        List<UUID> ordered = new ArrayList<>(TIED_IDS);
        ordered.sort(Comparator.comparing(UUID::toString).reversed());
        return ordered;
    }
}
