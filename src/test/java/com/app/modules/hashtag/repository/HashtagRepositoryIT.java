package com.app.modules.hashtag.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.app.modules.hashtag.entity.Hashtag;
import com.app.modules.hashtag.enums.HashtagStatus;
import com.app.testsupport.TestContainerImages;

@DataJpaTest(
        properties = {
            "spring.docker.compose.enabled=false",
            "spring.datasource.hikari.data-source-properties.stringtype=unspecified"
        })
@Testcontainers
class HashtagRepositoryIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(TestContainerImages.POSTGRES);

    @Autowired private HashtagRepository hashtagRepository;
    @Autowired private HashtagTrendingRepository hashtagTrendingRepository;
    @Autowired private JdbcClient jdbcClient;

    @DynamicPropertySource
    static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.enabled", () -> true);
    }

    @Test
    void searchByNameTrgm_bannedAndDeletedHashtags_areAbsent() {
        insertHashtag("trgmactive", HashtagStatus.ACTIVE);
        insertHashtag("trgmbanned", HashtagStatus.BANNED);
        insertHashtag("trgmdeleted", HashtagStatus.DELETED);

        List<String> names =
                hashtagRepository.searchByNameTrgm("trgm", 50, 0).stream()
                        .map(Hashtag::getName)
                        .toList();

        assertThat(names).contains("trgmactive");
        assertThat(names).doesNotContain("trgmbanned", "trgmdeleted");
    }

    @Test
    void upsertByName_bannedHashtag_leavesTheStatusBanned() {
        insertHashtag("resurrect", HashtagStatus.BANNED);

        hashtagRepository.upsertByName("resurrect");

        assertThat(hashtagRepository.findByName("resurrect").orElseThrow().getStatus())
                .isEqualTo(HashtagStatus.BANNED);
    }

    @Test
    void upsertByName_deletedHashtag_leavesTheStatusDeleted() {
        insertHashtag("gonetag", HashtagStatus.DELETED);

        hashtagRepository.upsertByName("gonetag");

        assertThat(hashtagRepository.findByName("gonetag").orElseThrow().getStatus())
                .isEqualTo(HashtagStatus.DELETED);
    }

    @Test
    void upsertByName_absentName_createsAnActiveHashtag() {
        hashtagRepository.upsertByName("brandnewtag");

        assertThat(hashtagRepository.findByName("brandnewtag").orElseThrow().getStatus())
                .isEqualTo(HashtagStatus.ACTIVE);
    }

    @Test
    void findBannedNames_mixedNames_returnsOnlyTheBannedOnes() {
        insertHashtag("subsetactive", HashtagStatus.ACTIVE);
        insertHashtag("subsetbanned", HashtagStatus.BANNED);
        insertHashtag("subsetdeleted", HashtagStatus.DELETED);

        assertThat(
                        hashtagRepository.findBannedNames(
                                List.of(
                                        "subsetactive",
                                        "subsetbanned",
                                        "subsetdeleted",
                                        "subsetmissing")))
                .containsExactly("subsetbanned");
    }

    @Test
    void administrativeCreate_duplicateName_failsAtTheDatabaseLayer() {
        // The administrative create carries no ON CONFLICT clause, unlike upsertByName, so a second
        // create of the same name must surface a conflict rather than silently report success
        // against a row somebody else owns.
        hashtagRepository.saveAndFlush(adminCreated("dupetag", HashtagStatus.BANNED, "first"));

        assertThatThrownBy(
                        () ->
                                hashtagRepository.saveAndFlush(
                                        adminCreated("dupetag", HashtagStatus.BANNED, "second")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void administrativeCreate_assignsAnIdentifierWithoutANativeInsert() {
        Hashtag created =
                hashtagRepository.saveAndFlush(
                        adminCreated("generatedid", HashtagStatus.ACTIVE, "seeded"));

        assertThat(created.getId()).isNotNull();
        assertThat(hashtagRepository.findByName("generatedid")).isPresent();
    }

    private static Hashtag adminCreated(String name, HashtagStatus status, String note) {
        return Hashtag.builder()
                .name(name)
                .status(status)
                .statusNote(note)
                .statusAt(OffsetDateTime.now())
                .build();
    }

    @Test
    void findAdminPage_spansEveryStatusAndPagesStablyAcrossACreatedAtTie() {
        // One shared created_at across all six rows, so every page boundary lands on a tie and the
        // id tiebreaker is the only thing keeping the two pages disjoint.
        OffsetDateTime shared = OffsetDateTime.parse("2026-01-01T00:00:00Z");
        for (int i = 0; i < 6; i++) {
            insertHashtag("tiedtag" + i, HashtagStatus.values()[i % 3], shared);
        }

        List<Hashtag> firstPage = hashtagRepository.findAdminPage(null, null, null, 3);
        Hashtag last = firstPage.get(2);
        List<Hashtag> secondPage =
                hashtagRepository.findAdminPage(null, last.getCreatedAt(), last.getId(), 3);

        assertThat(firstPage).hasSize(3);
        assertThat(secondPage).isNotEmpty();
        assertThat(secondPage.stream().map(Hashtag::getId))
                .doesNotContainAnyElementsOf(firstPage.stream().map(Hashtag::getId).toList());
        assertThat(firstPage.stream().map(Hashtag::getStatus).distinct()).hasSizeGreaterThan(1);
    }

    @Test
    void findAdminPage_statusFilter_returnsOnlyThatStatus() {
        insertHashtag("filteractive", HashtagStatus.ACTIVE);
        insertHashtag("filterbanned", HashtagStatus.BANNED);

        assertThat(hashtagRepository.findAdminPage(HashtagStatus.BANNED, null, null, 50))
                .extracting(Hashtag::getStatus)
                .containsOnly(HashtagStatus.BANNED);
    }

    @Test
    void searchAdmin_substringMatch_spansEveryStatus() {
        insertHashtag("needleactive", HashtagStatus.ACTIVE);
        insertHashtag("needlebanned", HashtagStatus.BANNED);
        insertHashtag("needledeleted", HashtagStatus.DELETED);
        insertHashtag("unrelated", HashtagStatus.ACTIVE);

        assertThat(hashtagRepository.searchAdmin("needle", null, null, null, 50))
                .extracting(Hashtag::getName)
                .containsExactlyInAnyOrder("needleactive", "needlebanned", "needledeleted");
    }

    @Test
    void searchAdmin_wildcardInQueryText_isMatchedLiterally() {
        insertHashtag("percentfree", HashtagStatus.ACTIVE);

        assertThat(hashtagRepository.searchAdmin("%", null, null, null, 50)).isEmpty();
    }

    @Test
    void deleteAllByHashtagId_removesEverySnapshotRowForThatHashtag() {
        UUID kept = insertHashtag("trendkept", HashtagStatus.ACTIVE);
        UUID purged = insertHashtag("trendpurged", HashtagStatus.ACTIVE);
        insertTrending(purged, OffsetDateTime.parse("2026-01-01T00:00:00Z"));
        insertTrending(purged, OffsetDateTime.parse("2026-01-02T00:00:00Z"));
        insertTrending(kept, OffsetDateTime.parse("2026-01-01T00:00:00Z"));

        assertThat(hashtagTrendingRepository.deleteAllByHashtagId(purged)).isEqualTo(2);

        assertThat(countTrending(purged)).isZero();
        assertThat(countTrending(kept)).isEqualTo(1);
    }

    @Test
    void findNamesByPostIds_manyPosts_groupsNamesPerPostOrderedByNameWithNoStatusFilter() {
        UUID author = insertUser();
        UUID tagged = insertPost(author);
        UUID otherTagged = insertPost(author);
        UUID untagged = insertPost(author);
        link(tagged, insertHashtag("bulkbeta", HashtagStatus.ACTIVE));
        link(tagged, insertHashtag("bulkalpha", HashtagStatus.ACTIVE));
        link(tagged, insertHashtag("bulkbanned", HashtagStatus.BANNED));
        link(otherTagged, insertHashtag("bulkzeta", HashtagStatus.ACTIVE));

        List<PostHashtagNameProjection> rows =
                hashtagRepository.findNamesByPostIds(List.of(tagged, otherTagged, untagged));

        assertThat(rows.stream().filter(r -> r.getPostId().equals(tagged)).map(r -> r.getName()))
                .containsExactly("bulkalpha", "bulkbanned", "bulkbeta");
        assertThat(
                        rows.stream()
                                .filter(r -> r.getPostId().equals(otherTagged))
                                .map(r -> r.getName()))
                .containsExactly("bulkzeta");
        assertThat(rows).noneMatch(r -> r.getPostId().equals(untagged));
        assertThat(hashtagRepository.findNamesByPostId(tagged))
                .containsExactly("bulkalpha", "bulkbanned", "bulkbeta");
    }

    private UUID insertHashtag(String name, HashtagStatus status) {
        return insertHashtag(name, status, null);
    }

    private UUID insertHashtag(String name, HashtagStatus status, OffsetDateTime createdAt) {
        return jdbcClient
                .sql(
                        """
						INSERT INTO hashtags (name, status, created_at)
						VALUES (:name, CAST(:status AS hashtag_status), COALESCE(:createdAt, NOW()))
						RETURNING id
						""")
                .param("name", name)
                .param("status", status.toJson())
                .param("createdAt", createdAt)
                .query(UUID.class)
                .single();
    }

    private void insertTrending(UUID hashtagId, OffsetDateTime periodStart) {
        jdbcClient
                .sql(
                        """
						INSERT INTO hashtag_trending
							(hashtag_id, period_start, period_end, post_count, rank)
						VALUES (:hashtagId, :periodStart, :periodStart, 1, 1)
						""")
                .param("hashtagId", hashtagId)
                .param("periodStart", periodStart)
                .update();
    }

    private long countTrending(UUID hashtagId) {
        return jdbcClient
                .sql("SELECT COUNT(*) FROM hashtag_trending WHERE hashtag_id = :hashtagId")
                .param("hashtagId", hashtagId)
                .query(Long.class)
                .single();
    }

    private UUID insertUser() {
        UUID id = UUID.randomUUID();
        String username = "bulk_" + id.toString().substring(0, 8);
        jdbcClient
                .sql(
                        "INSERT INTO users (id, username, email, role, status, is_private,"
                                + " is_verified) VALUES (:id, :username, :email, 'user', 'active',"
                                + " FALSE, TRUE)")
                .param("id", id)
                .param("username", username)
                .param("email", username + "@test.local")
                .update();
        return id;
    }

    private UUID insertPost(UUID author) {
        UUID id = UUID.randomUUID();
        jdbcClient
                .sql(
                        "INSERT INTO posts (id, user_id, caption, post_type, status) VALUES"
                                + " (:id, :author, 'caption', 'text', 'published')")
                .param("id", id)
                .param("author", author)
                .update();
        return id;
    }

    private void link(UUID postId, UUID hashtagId) {
        jdbcClient
                .sql("INSERT INTO post_hashtags (post_id, hashtag_id) VALUES (:post, :tag)")
                .param("post", postId)
                .param("tag", hashtagId)
                .update();
    }
}
