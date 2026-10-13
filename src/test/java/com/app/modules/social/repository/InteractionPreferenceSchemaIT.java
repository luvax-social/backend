package com.app.modules.social.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.app.modules.post.entity.PostNotInterested;
import com.app.modules.post.entity.PostNotInterestedId;
import com.app.modules.post.repository.PostNotInterestedRepository;
import com.app.modules.social.entity.AccountShowFewer;
import com.app.modules.social.entity.AccountShowFewerId;
import com.app.modules.social.entity.HiddenWord;
import com.app.modules.social.entity.InteractionLimit;
import com.app.modules.social.entity.UserHiddenWord;
import com.app.modules.social.entity.UserMute;
import com.app.modules.social.entity.UserMuteId;
import com.app.testsupport.TestContainerImages;

@DataJpaTest(
        properties = {
            "spring.docker.compose.enabled=false",
            "spring.datasource.hikari.data-source-properties.stringtype=unspecified"
        })
@Testcontainers
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class InteractionPreferenceSchemaIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(TestContainerImages.POSTGRES);

    private static final OffsetDateTime LONG_AGO = OffsetDateTime.parse("2000-01-01T00:00:00Z");

    private final UserMuteRepository userMuteRepository;
    private final AccountShowFewerRepository accountShowFewerRepository;
    private final InteractionLimitRepository interactionLimitRepository;
    private final HiddenWordRepository hiddenWordRepository;
    private final UserHiddenWordRepository userHiddenWordRepository;
    private final PostNotInterestedRepository postNotInterestedRepository;
    private final JdbcClient jdbcClient;

    InteractionPreferenceSchemaIT(
            UserMuteRepository userMuteRepository,
            AccountShowFewerRepository accountShowFewerRepository,
            InteractionLimitRepository interactionLimitRepository,
            HiddenWordRepository hiddenWordRepository,
            UserHiddenWordRepository userHiddenWordRepository,
            PostNotInterestedRepository postNotInterestedRepository,
            JdbcClient jdbcClient) {
        this.userMuteRepository = userMuteRepository;
        this.accountShowFewerRepository = accountShowFewerRepository;
        this.interactionLimitRepository = interactionLimitRepository;
        this.hiddenWordRepository = hiddenWordRepository;
        this.userHiddenWordRepository = userHiddenWordRepository;
        this.postNotInterestedRepository = postNotInterestedRepository;
        this.jdbcClient = jdbcClient;
    }

    @DynamicPropertySource
    static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.enabled", () -> true);
    }

    @Test
    void migrations_createEveryTable() {
        List<String> tables =
                jdbcClient
                        .sql(
                                """
								SELECT table_name FROM information_schema.tables
								WHERE table_schema = 'public' AND table_name IN (
									'user_mutes', 'account_show_fewer', 'interaction_limits',
									'post_not_interested', 'hidden_words', 'user_hidden_words')
								""")
                        .query(String.class)
                        .list();

        assertThat(tables)
                .containsExactlyInAnyOrder(
                        "user_mutes",
                        "account_show_fewer",
                        "interaction_limits",
                        "post_not_interested",
                        "hidden_words",
                        "user_hidden_words");
    }

    @Test
    void migrations_declareExpectedPrimaryKeys() {
        assertThat(primaryKeyColumns("user_mutes")).containsExactly("user_id", "muted_user_id");
        assertThat(primaryKeyColumns("account_show_fewer")).containsExactly("user_id", "author_id");
        assertThat(primaryKeyColumns("interaction_limits")).containsExactly("user_id");
        assertThat(primaryKeyColumns("post_not_interested")).containsExactly("user_id", "post_id");
        assertThat(primaryKeyColumns("hidden_words")).containsExactly("id");
        assertThat(primaryKeyColumns("user_hidden_words")).containsExactly("id");
    }

    @Test
    void userSettings_hiddenWordsSystemEnabledDefaultsToTrue() {
        UUID userId = insertUser("settingsowner");
        jdbcClient
                .sql("INSERT INTO user_settings(user_id) VALUES (:userId) ON CONFLICT DO NOTHING")
                .param("userId", userId)
                .update();

        Boolean enabled =
                jdbcClient
                        .sql(
                                "SELECT hidden_words_system_enabled FROM user_settings"
                                        + " WHERE user_id = :userId")
                        .param("userId", userId)
                        .query(Boolean.class)
                        .single();

        assertThat(enabled).isTrue();
    }

    @Test
    void userMutes_rejectSelfMute() {
        UUID userId = insertUser("selfmuter");

        assertThatThrownBy(() -> insertMute(userId, userId, true, true))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void userMutes_rejectRowWithBothScopesOff() {
        UUID userId = insertUser("muter");
        UUID targetId = insertUser("mutee");

        assertThatThrownBy(() -> insertMute(userId, targetId, false, false))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void userMutes_rejectDuplicatePair() {
        UUID userId = insertUser("dupmuter");
        UUID targetId = insertUser("dupmutee");
        insertMute(userId, targetId, true, false);

        assertThatThrownBy(() -> insertMute(userId, targetId, false, true))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void userMutes_touchUpdatedAtOnUpdate() {
        UUID userId = insertUser("trigmuter");
        UUID targetId = insertUser("trigmutee");
        jdbcClient
                .sql(
                        """
						INSERT INTO user_mutes(user_id, muted_user_id, mute_posts, mute_stories,
											created_at, updated_at)
						VALUES (:userId, :targetId, TRUE, FALSE, :past, :past)
						""")
                .param("userId", userId)
                .param("targetId", targetId)
                .param("past", LONG_AGO)
                .update();

        jdbcClient
                .sql(
                        "UPDATE user_mutes SET mute_stories = TRUE"
                                + " WHERE user_id = :userId AND muted_user_id = :targetId")
                .param("userId", userId)
                .param("targetId", targetId)
                .update();

        OffsetDateTime updatedAt =
                jdbcClient
                        .sql(
                                "SELECT updated_at FROM user_mutes"
                                        + " WHERE user_id = :userId AND muted_user_id = :targetId")
                        .param("userId", userId)
                        .param("targetId", targetId)
                        .query(OffsetDateTime.class)
                        .single();
        assertThat(updatedAt).isAfter(LONG_AGO);
    }

    @Test
    void userMutes_cascadeFromEitherUser() {
        UUID userId = insertUser("cascmuter");
        UUID targetId = insertUser("cascmutee");
        UUID otherId = insertUser("cascother");
        insertMute(userId, targetId, true, true);
        insertMute(otherId, userId, true, true);

        deleteUser(userId);

        assertThat(count("user_mutes", "user_id = :id OR muted_user_id = :id", userId)).isZero();
    }

    @Test
    void userMutes_saveAndFindRoundTrip() {
        UUID userId = insertUser("rtmuter");
        UUID targetId = insertUser("rtmutee");
        UserMuteId id = new UserMuteId(userId, targetId);

        userMuteRepository.saveAndFlush(
                UserMute.builder().id(id).mutePosts(true).muteStories(false).build());

        UserMute found = userMuteRepository.findById(id).orElseThrow();
        assertThat(found.isMutePosts()).isTrue();
        assertThat(found.isMuteStories()).isFalse();
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getUpdatedAt()).isNotNull();
    }

    @Test
    void accountShowFewer_rejectSelfRow() {
        UUID userId = insertUser("selffewer");

        assertThatThrownBy(() -> insertShowFewer(userId, userId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void accountShowFewer_cascadeFromEitherUser() {
        UUID userId = insertUser("cascfewer");
        UUID authorId = insertUser("cascauthor");
        UUID otherId = insertUser("cascfewerother");
        insertShowFewer(userId, authorId);
        insertShowFewer(otherId, userId);

        deleteUser(userId);

        assertThat(count("account_show_fewer", "user_id = :id OR author_id = :id", userId))
                .isZero();
    }

    @Test
    void accountShowFewer_saveAndFindRoundTrip() {
        UUID userId = insertUser("rtfewer");
        UUID authorId = insertUser("rtfewerauthor");
        AccountShowFewerId id = new AccountShowFewerId(userId, authorId);

        accountShowFewerRepository.saveAndFlush(AccountShowFewer.builder().id(id).build());

        AccountShowFewer found = accountShowFewerRepository.findById(id).orElseThrow();
        assertThat(found.getCreatedAt()).isNotNull();
    }

    @Test
    void interactionLimits_rejectRowWithBothTogglesOff() {
        UUID userId = insertUser("nolimit");

        assertThatThrownBy(() -> insertLimit(userId, false, false))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void interactionLimits_rejectSecondRowForSameUser() {
        UUID userId = insertUser("duplimit");
        insertLimit(userId, true, false);

        assertThatThrownBy(() -> insertLimit(userId, false, true))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void interactionLimits_touchUpdatedAtOnUpdate() {
        UUID userId = insertUser("triglimit");
        jdbcClient
                .sql(
                        """
						INSERT INTO interaction_limits(user_id, limit_non_followers,
													limit_recent_followers, expires_at,
													created_at, updated_at)
						VALUES (:userId, TRUE, FALSE, NOW() + INTERVAL '1 day', :past, :past)
						""")
                .param("userId", userId)
                .param("past", LONG_AGO)
                .update();

        jdbcClient
                .sql(
                        "UPDATE interaction_limits SET limit_recent_followers = TRUE"
                                + " WHERE user_id = :id")
                .param("id", userId)
                .update();

        OffsetDateTime updatedAt =
                jdbcClient
                        .sql("SELECT updated_at FROM interaction_limits WHERE user_id = :id")
                        .param("id", userId)
                        .query(OffsetDateTime.class)
                        .single();
        assertThat(updatedAt).isAfter(LONG_AGO);
    }

    @Test
    void interactionLimits_cascadeFromUser() {
        UUID userId = insertUser("casclimit");
        insertLimit(userId, true, true);

        deleteUser(userId);

        assertThat(count("interaction_limits", "user_id = :id", userId)).isZero();
    }

    @Test
    void interactionLimits_saveAndFindRoundTrip() {
        UUID userId = insertUser("rtlimit");
        OffsetDateTime expiresAt = OffsetDateTime.now().plusDays(7);

        interactionLimitRepository.saveAndFlush(
                InteractionLimit.builder()
                        .userId(userId)
                        .limitNonFollowers(true)
                        .limitRecentFollowers(false)
                        .expiresAt(expiresAt)
                        .build());

        InteractionLimit found = interactionLimitRepository.findById(userId).orElseThrow();
        assertThat(found.isLimitNonFollowers()).isTrue();
        assertThat(found.isLimitRecentFollowers()).isFalse();
        assertThat(found.getExpiresAt().toInstant())
                .isCloseTo(expiresAt.toInstant(), within(1, ChronoUnit.MILLIS));
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getUpdatedAt()).isNotNull();
    }

    @Test
    void postNotInterested_cascadeFromUser() {
        UUID userId = insertUser("cascni");
        UUID postId = insertPost(insertUser("cascniauthor"));
        insertNotInterested(userId, postId);

        deleteUser(userId);

        assertThat(count("post_not_interested", "user_id = :id", userId)).isZero();
    }

    @Test
    void postNotInterested_cascadeFromPost() {
        UUID userId = insertUser("cascnipost");
        UUID postId = insertPost(insertUser("cascnipostauthor"));
        insertNotInterested(userId, postId);

        jdbcClient.sql("DELETE FROM posts WHERE id = :id").param("id", postId).update();

        assertThat(count("post_not_interested", "post_id = :id", postId)).isZero();
    }

    @Test
    void postNotInterested_rejectDuplicatePair() {
        UUID userId = insertUser("dupni");
        UUID postId = insertPost(insertUser("dupniauthor"));
        insertNotInterested(userId, postId);

        assertThatThrownBy(() -> insertNotInterested(userId, postId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void postNotInterested_saveAndFindRoundTrip() {
        UUID userId = insertUser("rtni");
        UUID postId = insertPost(insertUser("rtniauthor"));
        PostNotInterestedId id = new PostNotInterestedId(userId, postId);

        postNotInterestedRepository.saveAndFlush(PostNotInterested.builder().id(id).build());

        PostNotInterested found = postNotInterestedRepository.findById(id).orElseThrow();
        assertThat(found.getCreatedAt()).isNotNull();
    }

    @Test
    void hiddenWords_rejectDuplicateNormalizedPhrase() {
        insertSystemWord("Bad Word", "bad word");

        assertThatThrownBy(() -> insertSystemWord("BAD  WORD", "bad word"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void hiddenWords_touchUpdatedAtOnUpdate() {
        UUID id =
                jdbcClient
                        .sql(
                                """
								INSERT INTO hidden_words(phrase, normalized_phrase,
														created_at, updated_at)
								VALUES ('Trig', 'trig', :past, :past)
								RETURNING id
								""")
                        .param("past", LONG_AGO)
                        .query(UUID.class)
                        .single();

        jdbcClient
                .sql("UPDATE hidden_words SET phrase = 'Trigger' WHERE id = :id")
                .param("id", id)
                .update();

        OffsetDateTime updatedAt =
                jdbcClient
                        .sql("SELECT updated_at FROM hidden_words WHERE id = :id")
                        .param("id", id)
                        .query(OffsetDateTime.class)
                        .single();
        assertThat(updatedAt).isAfter(LONG_AGO);
    }

    @Test
    void hiddenWords_keepRowAndClearCreatorWhenCreatorIsDeleted() {
        UUID adminId = insertUser("wordcreator");
        UUID wordId =
                jdbcClient
                        .sql(
                                """
								INSERT INTO hidden_words(phrase, normalized_phrase, created_by)
								VALUES ('Kept', 'kept', :adminId)
								RETURNING id
								""")
                        .param("adminId", adminId)
                        .query(UUID.class)
                        .single();

        deleteUser(adminId);

        assertThat(count("hidden_words", "id = :id", wordId)).isOne();
        UUID createdBy =
                jdbcClient
                        .sql("SELECT created_by FROM hidden_words WHERE id = :id")
                        .param("id", wordId)
                        .query(UUID.class)
                        .optional()
                        .orElse(null);
        assertThat(createdBy).isNull();
    }

    @Test
    void hiddenWords_saveAndFindRoundTrip() {
        HiddenWord saved =
                hiddenWordRepository.saveAndFlush(
                        HiddenWord.builder()
                                .phrase("Round Trip")
                                .normalizedPhrase("round trip")
                                .build());

        HiddenWord found = hiddenWordRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getPhrase()).isEqualTo("Round Trip");
        assertThat(found.getNormalizedPhrase()).isEqualTo("round trip");
        assertThat(found.getCreatedBy()).isNull();
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getUpdatedAt()).isNotNull();
    }

    @Test
    void userHiddenWords_rejectDuplicateNormalizedPhrasePerUser() {
        UUID userId = insertUser("dupword");
        insertUserWord(userId, "Spam", "spam");

        assertThatThrownBy(() -> insertUserWord(userId, "SPAM", "spam"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void userHiddenWords_allowSamePhraseForDifferentUsers() {
        UUID firstId = insertUser("wordfirst");
        UUID secondId = insertUser("wordsecond");
        insertUserWord(firstId, "Shared", "shared");

        insertUserWord(secondId, "Shared", "shared");

        assertThat(count("user_hidden_words", "normalized_phrase = 'shared'", null)).isEqualTo(2);
    }

    @Test
    void userHiddenWords_cascadeFromUser() {
        UUID userId = insertUser("cascword");
        insertUserWord(userId, "Gone", "gone");

        deleteUser(userId);

        assertThat(count("user_hidden_words", "user_id = :id", userId)).isZero();
    }

    @Test
    void userHiddenWords_saveAndFindRoundTrip() {
        UUID userId = insertUser("rtword");

        UserHiddenWord saved =
                userHiddenWordRepository.saveAndFlush(
                        UserHiddenWord.builder()
                                .userId(userId)
                                .phrase("Round Trip")
                                .normalizedPhrase("round trip")
                                .build());

        UserHiddenWord found = userHiddenWordRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getUserId()).isEqualTo(userId);
        assertThat(found.getPhrase()).isEqualTo("Round Trip");
        assertThat(found.getNormalizedPhrase()).isEqualTo("round trip");
        assertThat(found.getCreatedAt()).isNotNull();
    }

    private List<String> primaryKeyColumns(String table) {
        return jdbcClient
                .sql(
                        """
						SELECT a.attname
						FROM pg_index i
						JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = ANY (i.indkey)
						WHERE i.indrelid = CAST(:table AS regclass) AND i.indisprimary
						ORDER BY array_position(i.indkey::int2[], a.attnum)
						""")
                .param("table", table)
                .query(String.class)
                .list();
    }

    private long count(String table, String where, UUID id) {
        var statement = jdbcClient.sql("SELECT COUNT(*) FROM " + table + " WHERE " + where);
        if (id != null) {
            statement = statement.param("id", id);
        }
        return statement.query(Long.class).single();
    }

    private UUID insertUser(String username) {
        return jdbcClient
                .sql(
                        """
						INSERT INTO users(username, email, display_name)
						VALUES (:username, :email, :username)
						RETURNING id
						""")
                .param("username", username)
                .param("email", username + "@example.com")
                .query(UUID.class)
                .single();
    }

    private void deleteUser(UUID userId) {
        jdbcClient.sql("DELETE FROM users WHERE id = :id").param("id", userId).update();
    }

    private UUID insertPost(UUID authorId) {
        return jdbcClient
                .sql("INSERT INTO posts(user_id) VALUES (:authorId) RETURNING id")
                .param("authorId", authorId)
                .query(UUID.class)
                .single();
    }

    private void insertMute(UUID userId, UUID targetId, boolean posts, boolean stories) {
        jdbcClient
                .sql(
                        """
						INSERT INTO user_mutes(user_id, muted_user_id, mute_posts, mute_stories)
						VALUES (:userId, :targetId, :posts, :stories)
						""")
                .param("userId", userId)
                .param("targetId", targetId)
                .param("posts", posts)
                .param("stories", stories)
                .update();
    }

    private void insertShowFewer(UUID userId, UUID authorId) {
        jdbcClient
                .sql(
                        "INSERT INTO account_show_fewer(user_id, author_id)"
                                + " VALUES (:userId, :authorId)")
                .param("userId", userId)
                .param("authorId", authorId)
                .update();
    }

    private void insertLimit(UUID userId, boolean nonFollowers, boolean recentFollowers) {
        jdbcClient
                .sql(
                        """
						INSERT INTO interaction_limits(user_id, limit_non_followers,
													limit_recent_followers, expires_at)
						VALUES (:userId, :nonFollowers, :recentFollowers, NOW() + INTERVAL '1 day')
						""")
                .param("userId", userId)
                .param("nonFollowers", nonFollowers)
                .param("recentFollowers", recentFollowers)
                .update();
    }

    private void insertNotInterested(UUID userId, UUID postId) {
        jdbcClient
                .sql(
                        "INSERT INTO post_not_interested(user_id, post_id)"
                                + " VALUES (:userId, :postId)")
                .param("userId", userId)
                .param("postId", postId)
                .update();
    }

    private void insertSystemWord(String phrase, String normalized) {
        jdbcClient
                .sql("INSERT INTO hidden_words(phrase, normalized_phrase) VALUES (:p, :n)")
                .param("p", phrase)
                .param("n", normalized)
                .update();
    }

    private void insertUserWord(UUID userId, String phrase, String normalized) {
        jdbcClient
                .sql(
                        "INSERT INTO user_hidden_words(user_id, phrase, normalized_phrase)"
                                + " VALUES (:userId, :p, :n)")
                .param("userId", userId)
                .param("p", phrase)
                .param("n", normalized)
                .update();
    }
}
