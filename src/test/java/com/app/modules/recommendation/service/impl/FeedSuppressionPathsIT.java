package com.app.modules.recommendation.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.app.common.response.CursorPageResponse;
import com.app.common.security.jwt.JwtTokenProvider;
import com.app.modules.mail.service.MailService;
import com.app.modules.post.dto.response.FeedPostResponse;
import com.app.modules.post.dto.response.PostResponse;
import com.app.modules.post.search.PostDocument;
import com.app.modules.post.service.PostByHashtagService;
import com.app.modules.post.service.PostSaveService;
import com.app.modules.post.service.PostSearchService;
import com.app.modules.post.service.PostService;
import com.app.modules.post.validation.PostTypeFilter;
import com.app.modules.recommendation.client.GorseClient;
import com.app.modules.recommendation.client.dto.GorseScore;
import com.app.modules.recommendation.repository.UserEventAnalyticsRepository;
import com.app.modules.recommendation.service.RecommendationFeedService;
import com.app.testsupport.TestContainerImages;

/**
 * Every path that serves a viewer's posts applies their own interaction preferences, and the paths
 * that must not apply them do not.
 *
 * <p>Gorse is stubbed at its client so the real candidate source runs: the personalized list, the
 * trending top-up and the popularity list all reach the real pipeline. PostgreSQL is real, which is
 * what the chronological fallback and the following feed read. The cases are grouped by what the
 * viewer did to the account or post: muting posts removes the account from every feed, asking to
 * see fewer removes it from For You and Explore but not from Following, and marking a post not
 * interested removes that one post everywhere.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=dev",
            "spring.docker.compose.enabled=false",
            "spring.autoconfigure.exclude="
                    + "org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration"
        })
@Testcontainers
@AutoConfigureTestRestTemplate
class FeedSuppressionPathsIT {

    private static final OffsetDateTime BASE = OffsetDateTime.now(ZoneOffset.UTC).minusDays(1);

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(TestContainerImages.POSTGRES);

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");
        registry.add("JWT_SECRET", () -> "feed-suppression-paths-it-secret-32ch!");
        registry.add("JWT_ISSUER", () -> "https://feed-suppression.it.local");
        registry.add("JWT_AUDIENCE", () -> "App");
        registry.add("ACCESS_TOKEN_TTL", () -> 900L);
        registry.add("REFRESH_TOKEN_TTL", () -> 3600L);
        registry.add("APP_BASE_URL", () -> "http://localhost:8080");
        registry.add("CORS_ALLOWED_ORIGINS", () -> "http://localhost:3000");
        registry.add("RESEND_API_KEY", () -> "re_test_dummy_key");
        registry.add("MAIL_FROM_ADDRESS", () -> "noreply@test.local");
        registry.add("MAIL_FROM_NAME", () -> "App IT");
        registry.add("MAIL_APP_NAME", () -> "App");
        registry.add("FRONTEND_BASE_URL", () -> "http://localhost:3000");
        registry.add("GOOGLE_CLIENT_ID", () -> "test-client-id");
        registry.add("GOOGLE_CLIENT_SECRET", () -> "test-client-secret");
        registry.add(
                "spring.datasource.hikari.data-source-properties.stringtype", () -> "unspecified");
        registry.add("app.outbox.publisher.enabled", () -> false);
        registry.add("app.hashtag.seed.enabled", () -> false);
        registry.add("app.post.seed.enabled", () -> false);
    }

    // External services: the recommender, the search index and the analytics store the top-up
    // reads. Everything between them is the real application.
    @MockitoBean private MailService mailService;
    @MockitoBean private GorseClient gorseClient;
    @MockitoSpyBean private ElasticsearchOperations elasticsearchOperations;
    @MockitoBean private UserEventAnalyticsRepository userEventAnalyticsRepository;

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private RecommendationFeedService recommendationFeedService;
    @Autowired private PostService postService;
    @Autowired private PostSaveService postSaveService;
    @Autowired private PostSearchService postSearchService;
    @Autowired private PostByHashtagService postByHashtagService;

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM post_not_interested");
        jdbc.update("DELETE FROM post_saves");
        jdbc.update("DELETE FROM post_hashtags");
        jdbc.update("DELETE FROM hashtags");
        jdbc.update("DELETE FROM account_show_fewer");
        jdbc.update("DELETE FROM user_mutes");
        jdbc.update("DELETE FROM follows");
        jdbc.update("DELETE FROM posts");
        jdbc.update("DELETE FROM users");
    }

    @Test
    void following_excludesNotInterestedPostAndMutedPostsAuthor_keepsShowFewerAuthor() {
        UUID viewer = user("fol_viewer");
        UUID mutedPosts = user("fol_muted_posts");
        UUID showFewer = user("fol_show_fewer");
        UUID marked = user("fol_marked");
        UUID mutedStoriesOnly = user("fol_muted_stories");
        follow(viewer, mutedPosts);
        follow(viewer, showFewer);
        follow(viewer, marked);
        follow(viewer, mutedStoriesOnly);
        post(mutedPosts, 0);
        UUID showFewerPost = post(showFewer, 1);
        UUID notInterestedPost = post(marked, 2);
        UUID keptMarkedAuthorPost = post(marked, 3);
        UUID storiesOnlyPost = post(mutedStoriesOnly, 4);
        mute(viewer, mutedPosts, true, false);
        mute(viewer, mutedStoriesOnly, false, true);
        showFewer(viewer, showFewer);
        notInterested(viewer, notInterestedPost);

        assertThat(followingIds(viewer, null, 20))
                .containsExactly(showFewerPost, keptMarkedAuthorPost, storiesOnlyPost);
    }

    @Test
    void following_pagesKeepShowFewerAuthorsOnEveryPage() {
        UUID viewer = user("folpage_viewer");
        UUID showFewer = user("folpage_show_fewer");
        UUID other = user("folpage_other");
        follow(viewer, showFewer);
        follow(viewer, other);
        UUID o1 = post(other, 1);
        UUID s1 = post(showFewer, 2);
        UUID o2 = post(other, 3);
        UUID s2 = post(showFewer, 4);
        UUID o3 = post(other, 5);
        showFewer(viewer, showFewer);

        ResponseEntity<Map> first = getFollowing(viewer, null, 2);
        String cursor = endCursor(first);
        ResponseEntity<Map> second = getFollowing(viewer, cursor, 2);
        String cursor2 = endCursor(second);
        ResponseEntity<Map> third = getFollowing(viewer, cursor2, 2);

        assertThat(ids(first)).containsExactly(o1, s1);
        assertThat(ids(second)).containsExactly(o2, s2);
        assertThat(ids(third)).containsExactly(o3);
    }

    @Test
    void forYou_gorseResults_excludeNotInterestedShowFewerAndMuted() {
        Fixture f = rankedFixture();
        stubRecommend(f.candidates());
        stubTrending(List.of());

        List<UUID> served =
                ids(recommendationFeedService.getRecommendedFeed(f.viewer(), null, 20, false));

        assertThat(served).containsExactlyInAnyOrder(f.keptMarkedAuthorPost(), f.plainPost());
    }

    @Test
    void forYou_trendingTopUp_excludesAllThree() {
        Fixture f = rankedFixture();
        stubRecommend(List.of());
        stubTrending(f.candidates());

        List<UUID> served =
                ids(recommendationFeedService.getRecommendedFeed(f.viewer(), null, 20, false));

        assertThat(served).containsExactlyInAnyOrder(f.keptMarkedAuthorPost(), f.plainPost());
    }

    @Test
    void forYou_popularityFallback_excludesAllThree() {
        Fixture f = rankedFixture();
        when(gorseClient.popular(anyInt(), anyInt()))
                .thenAnswer(
                        invocation ->
                                (int) invocation.getArgument(1) == 0
                                        ? scores(f.candidates())
                                        : List.of());
        String popularCursor =
                Base64.getEncoder().encodeToString("p:0:0".getBytes(StandardCharsets.UTF_8));

        List<UUID> served =
                ids(
                        recommendationFeedService.getRecommendedFeed(
                                f.viewer(), popularCursor, 20, false));

        assertThat(served).containsExactlyInAnyOrder(f.keptMarkedAuthorPost(), f.plainPost());
    }

    @Test
    void forYou_chronologicalFallbackFirstPage_appliesShowFewer() {
        UUID viewer = user("fb_first_viewer");
        UUID mutedPosts = user("fb_first_muted");
        UUID showFewer = user("fb_first_show_fewer");
        UUID marked = user("fb_first_marked");
        follow(viewer, mutedPosts);
        follow(viewer, showFewer);
        follow(viewer, marked);
        post(mutedPosts, 3);
        post(showFewer, 4);
        UUID notInterestedPost = post(marked, 1);
        UUID kept = post(marked, 0);
        mute(viewer, mutedPosts, true, true);
        showFewer(viewer, showFewer);
        notInterested(viewer, notInterestedPost);
        stubRecommend(List.of());
        stubTrending(List.of());

        List<UUID> served =
                ids(recommendationFeedService.getRecommendedFeed(viewer, null, 20, false));

        assertThat(served).containsExactly(kept);
    }

    @Test
    void forYouFallbackContinuation_appliesShowFewer() {
        UUID viewer = user("fb_cont_viewer");
        UUID showFewer = user("fb_cont_show_fewer");
        UUID other = user("fb_cont_other");
        follow(viewer, showFewer);
        follow(viewer, other);
        UUID o1 = post(other, 1);
        post(showFewer, 2);
        UUID o2 = post(other, 3);
        post(showFewer, 4);
        UUID o3 = post(other, 5);
        showFewer(viewer, showFewer);
        stubRecommend(List.of());
        stubTrending(List.of());

        CursorPageResponse<FeedPostResponse> first =
                recommendationFeedService.getRecommendedFeed(viewer, null, 2, false);
        CursorPageResponse<FeedPostResponse> second =
                recommendationFeedService.getRecommendedFeed(
                        viewer, first.getPageInfo().getEndCursor(), 2, false);

        assertThat(ids(first)).containsExactly(o1, o2);
        assertThat(first.getPageInfo().isHasNextPage()).isTrue();
        assertThat(ids(second)).containsExactly(o3);
    }

    @Test
    void explore_excludesAllThree() {
        Fixture f = rankedFixture();
        stubRecommend(f.candidates());
        stubTrending(List.of());

        List<UUID> served =
                ids(recommendationFeedService.getRecommendedFeed(f.viewer(), null, 20, true));

        assertThat(served).containsExactlyInAnyOrder(f.keptMarkedAuthorPost(), f.plainPost());
    }

    @Test
    void profileSearchHashtag_unaffectedByMuteAndShowFewer() {
        UUID viewer = user("open_viewer");
        UUID muted = user("open_muted");
        UUID showFewer = user("open_show_fewer");
        UUID mutedPost = post(muted, 1);
        UUID showFewerPost = post(showFewer, 2);
        mute(viewer, muted, true, true);
        showFewer(viewer, showFewer);
        savePost(viewer, mutedPost);
        savePost(viewer, showFewerPost);
        UUID hashtag = hashtag("openTag", mutedPost, showFewerPost);
        stubSearchHits(mutedPost, showFewerPost);

        assertThat(
                        postService
                                .listUserPosts(viewer, muted, PostTypeFilter.empty(), null, 20)
                                .getContent())
                .extracting(PostResponse::id)
                .containsExactly(mutedPost);
        assertThat(
                        postService
                                .listUserPosts(viewer, showFewer, PostTypeFilter.empty(), null, 20)
                                .getContent())
                .extracting(PostResponse::id)
                .containsExactly(showFewerPost);
        assertThat(postSaveService.listSavedPosts(viewer, null, 20).getContent())
                .extracting(saved -> saved.post().id())
                .containsExactlyInAnyOrder(mutedPost, showFewerPost);
        assertThat(postSearchService.searchPosts(viewer, "caption", null, 20).getContent())
                .extracting(PostResponse::id)
                .containsExactlyInAnyOrder(mutedPost, showFewerPost);
        assertThat(postByHashtagService.findPostsByHashtag(viewer, hashtag, null, 20).getContent())
                .extracting(PostResponse::id)
                .containsExactlyInAnyOrder(mutedPost, showFewerPost);
    }

    private record Fixture(
            UUID viewer,
            UUID mutedPost,
            UUID showFewerPost,
            UUID notInterestedPost,
            UUID keptMarkedAuthorPost,
            UUID plainPost) {

        // Gorse's answer: the three that must be dropped first, the two that must survive last.
        List<UUID> candidates() {
            return List.of(
                    mutedPost, showFewerPost, notInterestedPost, keptMarkedAuthorPost, plainPost);
        }
    }

    // One viewer who muted an author's posts, asked to see fewer from a second author, marked one
    // post of a third author not interested, and has a fourth author with no preference at all.
    private Fixture rankedFixture() {
        UUID viewer = user("rk_viewer");
        UUID muted = user("rk_muted");
        UUID showFewer = user("rk_show_fewer");
        UUID marked = user("rk_marked");
        UUID plain = user("rk_plain");
        UUID mutedPost = post(muted, 5);
        UUID showFewerPost = post(showFewer, 4);
        UUID notInterestedPost = post(marked, 3);
        UUID keptMarkedAuthorPost = post(marked, 2);
        UUID plainPost = post(plain, 1);
        mute(viewer, muted, true, false);
        showFewer(viewer, showFewer);
        notInterested(viewer, notInterestedPost);
        return new Fixture(
                viewer,
                mutedPost,
                showFewerPost,
                notInterestedPost,
                keptMarkedAuthorPost,
                plainPost);
    }

    private void stubRecommend(List<UUID> postIds) {
        when(gorseClient.recommend(any(), anyInt(), anyInt()))
                .thenAnswer(
                        invocation ->
                                (int) invocation.getArgument(2) == 0 ? scores(postIds) : List.of());
    }

    private void stubTrending(List<UUID> postIds) {
        when(gorseClient.trending(anyInt(), anyInt()))
                .thenAnswer(
                        invocation ->
                                (int) invocation.getArgument(1) == 0 ? scores(postIds) : List.of());
    }

    private static List<GorseScore> scores(List<UUID> postIds) {
        double score = 10.0;
        java.util.ArrayList<GorseScore> scores = new java.util.ArrayList<>();
        for (UUID postId : postIds) {
            scores.add(new GorseScore(postId.toString(), score--));
        }
        return scores;
    }

    @SuppressWarnings("unchecked")
    private void stubSearchHits(UUID... postIds) {
        List<SearchHit<PostDocument>> hits = new java.util.ArrayList<>();
        for (UUID postId : postIds) {
            PostDocument document = new PostDocument();
            document.setId(postId.toString());
            SearchHit<PostDocument> hit = mock(SearchHit.class);
            when(hit.getContent()).thenReturn(document);
            hits.add(hit);
        }
        SearchHits<PostDocument> searchHits = mock(SearchHits.class);
        when(searchHits.getSearchHits()).thenReturn(hits);
        doReturn(searchHits)
                .when(elasticsearchOperations)
                .search(any(NativeQuery.class), eq(PostDocument.class));
    }

    private List<UUID> followingIds(UUID viewer, String cursor, int limit) {
        ResponseEntity<Map> response = getFollowing(viewer, cursor, limit);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return ids(response);
    }

    private ResponseEntity<Map> getFollowing(UUID viewer, String cursor, int limit) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtTokenProvider.generateAccessToken(viewer, "USER", 0));
        String path =
                "/api/v1/posts/feed?limit=" + limit + (cursor == null ? "" : "&cursor=" + cursor);
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
    }

    @SuppressWarnings("unchecked")
    private static List<UUID> ids(ResponseEntity<Map> response) {
        Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
        List<Map<String, Object>> content = (List<Map<String, Object>>) data.get("content");
        return content.stream().map(post -> UUID.fromString(post.get("id").toString())).toList();
    }

    @SuppressWarnings("unchecked")
    private static String endCursor(ResponseEntity<Map> response) {
        Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
        Map<String, Object> pageInfo = (Map<String, Object>) data.get("pageInfo");
        return (String) pageInfo.get("endCursor");
    }

    private static List<UUID> ids(CursorPageResponse<FeedPostResponse> page) {
        return page.getContent().stream().map(FeedPostResponse::id).toList();
    }

    private UUID user(String username) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users (id, username, email, role, status, is_private, is_verified)"
                        + " VALUES (?, ?, ?, CAST('user' AS user_role), 'active', FALSE, TRUE)",
                id,
                username,
                username + "@test.local");
        return id;
    }

    // Newer posts get a smaller minutesAgo, so a higher number is older.
    private UUID post(UUID author, int minutesAgo) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO posts (id, user_id, caption, post_type, status, created_at)"
                        + " VALUES (?, ?, 'caption', CAST('text' AS post_type),"
                        + " CAST('published' AS post_status), ?)",
                id,
                author,
                BASE.minusMinutes(minutesAgo));
        return id;
    }

    private void follow(UUID follower, UUID following) {
        jdbc.update(
                "INSERT INTO follows (follower_id, following_id, status)"
                        + " VALUES (?, ?, CAST('accepted' AS follow_status))",
                follower,
                following);
    }

    private void mute(UUID viewer, UUID muted, boolean mutePosts, boolean muteStories) {
        jdbc.update(
                "INSERT INTO user_mutes (user_id, muted_user_id, mute_posts, mute_stories)"
                        + " VALUES (?, ?, ?, ?)",
                viewer,
                muted,
                mutePosts,
                muteStories);
    }

    private void showFewer(UUID viewer, UUID author) {
        jdbc.update(
                "INSERT INTO account_show_fewer (user_id, author_id) VALUES (?, ?)",
                viewer,
                author);
    }

    private void notInterested(UUID viewer, UUID post) {
        jdbc.update(
                "INSERT INTO post_not_interested (user_id, post_id) VALUES (?, ?)", viewer, post);
    }

    private void savePost(UUID viewer, UUID post) {
        jdbc.update("INSERT INTO post_saves (user_id, post_id) VALUES (?, ?)", viewer, post);
    }

    private UUID hashtag(String name, UUID... posts) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO hashtags (id, name) VALUES (?, ?)", id, name);
        for (UUID post : posts) {
            jdbc.update("INSERT INTO post_hashtags (post_id, hashtag_id) VALUES (?, ?)", post, id);
        }
        return id;
    }
}
