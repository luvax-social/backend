package com.app.modules.admin.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.app.modules.admin.dto.response.AdminPostSummaryResponse;
import com.app.testsupport.TestContainerImages;

/**
 * Pins that reading an account's posts costs the same number of statements at one row and at
 * twenty.
 *
 * <p>Attaching related rows to a page is the shape that has produced an N+1 on this project twice:
 * once when hashtags were added to the post response, and once on the feed response. A statement
 * count that grows with the page is the defect, and a count that does not is the whole claim, so it
 * is measured rather than argued.
 *
 * <p>Counted at the {@link DataSource} rather than through Hibernate statistics, which is how
 * {@code CommentAuthorEmbeddingIT} does it: this repository issues plain SQL through {@link
 * JdbcClient} and never goes through a Hibernate session, so the session's counters would report
 * zero however many statements ran.
 */
@Testcontainers
@DataJpaTest
class AdminContentMediaStatementCountIT {

    @Container @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(TestContainerImages.POSTGRES);

    // The autowired DataSource is only here to make the slice start, which is what runs Flyway
    // and gives the container its schema.
    @Autowired private DataSource managedDataSource;

    private final AtomicInteger statements = new AtomicInteger();
    private JdbcClient jdbcClient;
    private AdminContentRepository repository;

    @BeforeEach
    void setUp() {
        assertThat(managedDataSource).isNotNull();
        // Deliberately not the managed DataSource. Inside the slice's transaction Spring hands
        // back the connection already bound to that DataSource, so a proxy around it is never
        // asked for a connection and counts nothing. A separate, unmanaged DataSource against the
        // same container has no transaction bound to it, so every statement goes through the
        // proxy. Writes through it commit immediately, which is why each test seeds its own
        // author rather than relying on the slice's rollback.
        DriverManagerDataSource raw = new DriverManagerDataSource();
        raw.setUrl(POSTGRES.getJdbcUrl());
        raw.setUsername(POSTGRES.getUsername());
        raw.setPassword(POSTGRES.getPassword());
        JdbcClient counting = JdbcClient.create(countingDataSource(raw, statements));
        this.jdbcClient = counting;
        this.repository = new AdminContentRepository(counting);
    }

    @Test
    void findPostsForUser_statementCountIsTheSameAtOneRowAndAtTwenty() {
        UUID oneAuthor = seedAuthor("count_one");
        seedPostsWithMedia(oneAuthor, 1);
        UUID manyAuthor = seedAuthor("count_many");
        seedPostsWithMedia(manyAuthor, 20);

        statements.set(0);
        List<AdminPostSummaryResponse> one = repository.findPostsForUser(oneAuthor, null, null, 50);
        int atOne = statements.get();

        statements.set(0);
        List<AdminPostSummaryResponse> many =
                repository.findPostsForUser(manyAuthor, null, null, 50);
        int atTwenty = statements.get();

        assertThat(one).hasSize(1);
        assertThat(many).hasSize(20);
        assertThat(atOne).as("statements at one row").isEqualTo(2);
        assertThat(atTwenty).as("statements at twenty rows").isEqualTo(atOne);
    }

    @Test
    void findPostsForUser_returnsTheAttachedMediaInCarouselOrder() {
        UUID author = seedAuthor("media_order");
        seedPostsWithMedia(author, 1);

        List<AdminPostSummaryResponse> rows = repository.findPostsForUser(author, null, null, 50);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).mediaUrls()).hasSize(2);
        assertThat(rows.get(0).mediaUrls().get(0)).endsWith("-0.jpg");
        assertThat(rows.get(0).mediaUrls().get(1)).endsWith("-1.jpg");
    }

    @Test
    void findPostsForUser_textPostCarriesAnEmptyMediaList() {
        UUID author = seedAuthor("no_media");
        jdbcClient
                .sql(
                        "INSERT INTO posts (user_id, caption, post_type, status)"
                                + " VALUES (:userId, 'text only', 'text', 'published')")
                .param("userId", author)
                .update();

        List<AdminPostSummaryResponse> rows = repository.findPostsForUser(author, null, null, 50);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).mediaUrls()).isEmpty();
    }

    private UUID seedAuthor(String username) {
        return jdbcClient
                .sql(
                        "INSERT INTO users (username, email, display_name, role, status)"
                                + " VALUES (:u, :u || '@example.com', :u, 'user', 'active')"
                                + " RETURNING id")
                .param("u", username)
                .query(UUID.class)
                .single();
    }

    private void seedPostsWithMedia(UUID author, int posts) {
        for (int p = 0; p < posts; p++) {
            UUID postId =
                    jdbcClient
                            .sql(
                                    "INSERT INTO posts (user_id, caption, post_type, status)"
                                            + " VALUES (:userId, :caption, 'carousel', 'published')"
                                            + " RETURNING id")
                            .param("userId", author)
                            .param("caption", "post " + p)
                            .query(UUID.class)
                            .single();
            // Two assets per post, so the media query returns more rows than there are posts and a
            // per-row read would be unmistakable in the count.
            for (int m = 0; m < 2; m++) {
                UUID assetId =
                        jdbcClient
                                .sql(
                                        "INSERT INTO media_assets (user_id, storage_key, cdn_url,"
                                                + " media_type, mime_type, file_size, width, height)"
                                                + " VALUES (:userId, :key, :url, 'image',"
                                                + " 'image/jpeg', 1024, 10, 10) RETURNING id")
                                .param("userId", author)
                                .param("key", author + "/" + p + "-" + m)
                                .param(
                                        "url",
                                        "https://cdn.example/"
                                                + author
                                                + "/"
                                                + p
                                                + "-"
                                                + m
                                                + ".jpg")
                                .query(UUID.class)
                                .single();
                jdbcClient
                        .sql(
                                "INSERT INTO post_media (post_id, media_asset_id, position)"
                                        + " VALUES (:postId, :assetId, :position)")
                        .param("postId", postId)
                        .param("assetId", assetId)
                        .param("position", m)
                        .update();
            }
        }
    }

    /**
     * Wraps a data source so every {@code prepareStatement} on a handed-out connection increments
     * the counter.
     *
     * <p>A dynamic proxy rather than a dependency: counting prepared statements is the only thing
     * needed here, and adding a proxying data source library to the build for one assertion would
     * be a larger change than the assertion.
     */
    private static DataSource countingDataSource(DataSource delegate, AtomicInteger counter) {
        InvocationHandler dataSourceHandler =
                (proxy, method, args) -> {
                    Object result = method.invoke(delegate, args);
                    if (result instanceof Connection connection) {
                        return countingConnection(connection, counter);
                    }
                    return result;
                };
        return (DataSource)
                Proxy.newProxyInstance(
                        AdminContentMediaStatementCountIT.class.getClassLoader(),
                        new Class<?>[] {DataSource.class},
                        dataSourceHandler);
    }

    private static Connection countingConnection(Connection delegate, AtomicInteger counter) {
        InvocationHandler handler =
                (proxy, method, args) -> {
                    if (method.getName().startsWith("prepare")) {
                        counter.incrementAndGet();
                    }
                    try {
                        return method.invoke(delegate, args);
                    } catch (java.lang.reflect.InvocationTargetException ex) {
                        throw ex.getCause() == null ? ex : ex.getCause();
                    }
                };
        return (Connection)
                Proxy.newProxyInstance(
                        AdminContentMediaStatementCountIT.class.getClassLoader(),
                        new Class<?>[] {Connection.class},
                        handler);
    }
}
