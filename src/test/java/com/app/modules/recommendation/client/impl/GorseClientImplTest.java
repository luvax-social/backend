package com.app.modules.recommendation.client.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.app.modules.recommendation.client.dto.GorseFeedback;
import com.app.modules.recommendation.client.dto.GorseItem;
import com.app.modules.recommendation.client.dto.GorseItemPage;
import com.app.modules.recommendation.client.dto.GorseScore;
import com.app.modules.recommendation.client.dto.GorseUser;

class GorseClientImplTest {

    private static final String API_KEY = "test-api-key";

    private MockRestServiceServer server;
    private GorseClientImpl client;

    @BeforeEach
    void setUp() {
        // Mirrors GorseClientConfig: the X-API-Key and X-API-Version default headers are set on
        // the RestClient, not by GorseClientImpl itself, so the test wires them the same way.
        RestClient.Builder builder =
                RestClient.builder()
                        .baseUrl("http://gorse.test")
                        .defaultHeader("X-API-Key", API_KEY)
                        .defaultHeader("X-API-Version", "2");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new GorseClientImpl(builder.build());
    }

    @Test
    void recommend_parsesScoredResponseAndSendsAuthHeaders() {
        UUID userId = UUID.randomUUID();
        server.expect(requestTo("http://gorse.test/api/recommend/" + userId + "?n=10&offset=0"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-API-Key", API_KEY))
                .andExpect(header("X-API-Version", "2"))
                .andRespond(
                        withSuccess(
                                "[{\"Id\":\"item-1\",\"Score\":5.5},{\"Id\":\"item-2\",\"Score\":3.1}]",
                                MediaType.APPLICATION_JSON));

        List<GorseScore> scores = client.recommend(userId, 10, 0);

        assertThat(scores)
                .containsExactly(new GorseScore("item-1", 5.5), new GorseScore("item-2", 3.1));
        server.verify();
    }

    @Test
    void recommend_emptyBody_returnsEmptyList() {
        UUID userId = UUID.randomUUID();
        server.expect(requestTo("http://gorse.test/api/recommend/" + userId + "?n=5&offset=2"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        List<GorseScore> scores = client.recommend(userId, 5, 2);

        assertThat(scores).isEmpty();
    }

    @Test
    void popular_callsNonPersonalizedPopularEndpoint() {
        server.expect(requestTo("http://gorse.test/api/non-personalized/popular?n=20&offset=0"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(
                        withSuccess(
                                "[{\"Id\":\"item-3\",\"Score\":100.0}]",
                                MediaType.APPLICATION_JSON));

        List<GorseScore> scores = client.popular(20, 0);

        assertThat(scores).containsExactly(new GorseScore("item-3", 100.0));
    }

    @Test
    void trending_callsNonPersonalizedTrendingEndpoint() {
        server.expect(requestTo("http://gorse.test/api/non-personalized/trending?n=15&offset=5"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(
                        withSuccess(
                                "[{\"Id\":\"item-4\",\"Score\":42.0}]",
                                MediaType.APPLICATION_JSON));

        List<GorseScore> scores = client.trending(15, 5);

        assertThat(scores).containsExactly(new GorseScore("item-4", 42.0));
        server.verify();
    }

    @Test
    void insertFeedback_postsFeedbackBatchWithAuthHeader() {
        GorseFeedback feedback =
                new GorseFeedback(
                        "like",
                        "user-1",
                        "item-1",
                        OffsetDateTime.parse("2026-07-26T00:00:00Z"),
                        1.0);
        server.expect(requestTo("http://gorse.test/api/feedback"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-API-Key", API_KEY))
                .andRespond(withSuccess("{\"RowAffected\":1}", MediaType.APPLICATION_JSON));

        client.insertFeedback(List.of(feedback));

        server.verify();
    }

    @Test
    void upsertFeedback_putsFeedbackBatchWithAuthHeader() {
        OffsetDateTime at = OffsetDateTime.parse("2026-09-01T10:00:00Z");
        server.expect(requestTo("http://gorse.test/api/feedback"))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(header("X-API-Key", API_KEY))
                .andExpect(
                        content()
                                .json(
                                        "[{\"FeedbackType\":\"like\",\"UserId\":\"u1\","
                                                + "\"ItemId\":\"i1\",\"Value\":2.5}]"))
                .andRespond(withSuccess("{\"RowAffected\":1}", MediaType.APPLICATION_JSON));

        client.upsertFeedback(List.of(new GorseFeedback("like", "u1", "i1", at, 2.5)));

        server.verify();
    }

    @Test
    void listItems_firstPage_sendsNoCursorAndParsesItemsWithTheNextCursor() {
        server.expect(requestTo("http://gorse.test/api/items?n=2"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-API-Key", API_KEY))
                .andRespond(
                        withSuccess(
                                "{\"Cursor\":\"next\",\"Items\":["
                                        + "{\"ItemId\":\"i1\",\"IsHidden\":false,"
                                        + "\"Categories\":[],\"Labels\":[\"a\"],"
                                        + "\"Timestamp\":\"2026-09-01T10:00:00Z\","
                                        + "\"Comment\":\"\"},"
                                        + "{\"ItemId\":\"i2\",\"IsHidden\":true,"
                                        + "\"Categories\":[],\"Labels\":[],"
                                        + "\"Timestamp\":\"2026-09-02T10:00:00Z\","
                                        + "\"Comment\":\"\"}]}",
                                MediaType.APPLICATION_JSON));

        GorseItemPage page = client.listItems(null, 2);

        assertThat(page.cursor()).isEqualTo("next");
        assertThat(page.items()).extracting(GorseItem::itemId).containsExactly("i1", "i2");
        assertThat(page.items()).extracting(GorseItem::hidden).containsExactly(false, true);
        server.verify();
    }

    @Test
    void listItems_laterPage_sendsTheCursorAndAnEmptyBodyIsAnEmptyPage() {
        server.expect(requestTo("http://gorse.test/api/items?n=5&cursor=next"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(
                        withSuccess("{\"Cursor\":\"\",\"Items\":[]}", MediaType.APPLICATION_JSON));

        GorseItemPage page = client.listItems("next", 5);

        assertThat(page.cursor()).isEmpty();
        assertThat(page.items()).isEmpty();
        server.verify();
    }

    @Test
    void upsertItems_postsItemBatch() {
        GorseItem item =
                new GorseItem(
                        "item-1",
                        false,
                        List.of(),
                        List.of("topic"),
                        OffsetDateTime.parse("2026-07-26T00:00:00Z"),
                        "");
        server.expect(requestTo("http://gorse.test/api/items"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"RowAffected\":1}", MediaType.APPLICATION_JSON));

        client.upsertItems(List.of(item));

        server.verify();
    }

    @Test
    void upsertUsers_postsUserBatch() {
        GorseUser user = new GorseUser("user-1", List.of("topic"), "comment");
        server.expect(requestTo("http://gorse.test/api/users"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"RowAffected\":1}", MediaType.APPLICATION_JSON));

        client.upsertUsers(List.of(user));

        server.verify();
    }

    @Test
    void hideItem_sendsPatchWithAuthHeader() {
        server.expect(requestTo("http://gorse.test/api/item/item-1"))
                .andExpect(method(HttpMethod.PATCH))
                .andExpect(header("X-API-Key", API_KEY))
                .andRespond(withSuccess("{\"RowAffected\":1}", MediaType.APPLICATION_JSON));

        client.hideItem("item-1");

        server.verify();
    }

    @Test
    void getFeedback_tupleHeld_readsTheUsersListOfThatTypeAndPicksTheItemIgnoringExtraFields() {
        server.expect(requestTo("http://gorse.test/api/user/user-1/feedback/read"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-API-Key", API_KEY))
                .andRespond(
                        withSuccess(
                                "[{\"FeedbackType\":\"read\",\"UserId\":\"user-1\","
                                        + "\"ItemId\":\"other\",\"Value\":1,"
                                        + "\"Timestamp\":\"2026-01-02T00:00:00Z\","
                                        + "\"Updated\":\"2026-01-02T00:00:00Z\","
                                        + "\"Comment\":\"\"},"
                                        + "{\"FeedbackType\":\"read\",\"UserId\":\"user-1\","
                                        + "\"ItemId\":\"item-1\",\"Value\":3.5,"
                                        + "\"Timestamp\":\"2026-01-02T00:00:00Z\","
                                        + "\"Updated\":\"2026-01-02T00:00:00Z\","
                                        + "\"Comment\":\"\"}]",
                                MediaType.APPLICATION_JSON));

        Optional<GorseFeedback> held = client.getFeedback("read", "user-1", "item-1");

        assertThat(held).get().extracting(GorseFeedback::value).isEqualTo(3.5);
        server.verify();
    }

    @Test
    void getFeedback_unknownUserOrTuple_isEmptyRatherThanAFailure() {
        server.expect(requestTo("http://gorse.test/api/user/nobody/feedback/like"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertThat(client.getFeedback("like", "nobody", "item-1")).isEmpty();
        server.verify();
    }
}
