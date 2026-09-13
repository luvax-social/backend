package com.app.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import com.app.modules.hashtag.controller.HashtagController;
import com.app.modules.message.controller.MessageController;
import com.app.modules.post.controller.HashtagPostController;
import com.app.modules.users.controller.UserController;

/**
 * Confirms constants for paths no controller serves have been removed, so every remaining constant
 * in {@link ApiConstants} corresponds to a route the application actually handles.
 */
class ApiConstantsUnroutedFieldsTest {

    @Test
    void unroutedConstants_noLongerExist() {
        // SEARCH was removed here as unrouted and deliberately reinstated by P5, which added
        // GET /api/v1/users/search. The four Messages constants were removed here as unrouted and
        // deliberately reinstated by the send/history/delete/read-state endpoints; see
        // reinstatedMessageConstants_areRoutedByAController below. The two Hashtags constants went
        // the same way when the by-name lookup and the posts-by-hashtag listing landed; see
        // reinstatedHashtagConstants_areRoutedByAController.
        assertThat(fieldNames(ApiConstants.Users.class)).doesNotContain("SUGGESTIONS", "ME_AVATAR");
        assertThat(fieldNames(ApiConstants.Posts.class)).doesNotContain("EXPLORE", "MEDIA");
        assertThat(fieldNames(ApiConstants.Media.class)).doesNotContain("BY_ID");
        assertThat(fieldNames(ApiConstants.Auth.class))
                .doesNotContain("OAUTH2_CALLBACK", "CHANGE_PASSWORD");
    }

    @Test
    void reinstatedSearchConstant_isRoutedByAController() {
        // The guard's contract is "no constant without a route", so a reinstated name must be
        // pinned to an actual handler rather than simply dropped from the exclusion list.
        assertThat(fieldNames(ApiConstants.Users.class)).contains("SEARCH");
        assertThat(ApiConstants.Users.SEARCH).isEqualTo("/search");
        assertThat(routedPaths(UserController.class))
                .contains(ApiConstants.Users.SEARCH, ApiConstants.Users.BY_USERNAME);
    }

    @Test
    void reinstatedMessageConstants_areRoutedByAController() {
        // Same contract as reinstatedSearchConstant_isRoutedByAController above, extended to the
        // three HTTP verbs the send/history/delete/read-state endpoints use.
        assertThat(fieldNames(ApiConstants.Messages.class))
                .contains("CONVERSATION_MESSAGES", "MESSAGE_BY_ID", "READ", "UNREAD_COUNT");

        String conversationMessages =
                ApiConstants.Messages.ROOT + ApiConstants.Messages.CONVERSATION_MESSAGES;
        String messageById = ApiConstants.Messages.ROOT + ApiConstants.Messages.MESSAGE_BY_ID;
        String read = ApiConstants.Messages.ROOT + ApiConstants.Messages.READ;
        String unreadCount = ApiConstants.Messages.ROOT + ApiConstants.Messages.UNREAD_COUNT;

        assertThat(routedPaths(MessageController.class))
                .contains(conversationMessages, unreadCount);
        assertThat(postRoutedPaths(MessageController.class)).contains(conversationMessages, read);
        assertThat(deleteRoutedPaths(MessageController.class)).contains(messageById);
    }

    @Test
    void reinstatedHashtagConstants_areRoutedByAController() {
        // Same contract as the two above. The handlers sit in different modules: the by-name
        // lookup is served by the hashtag module, and the posts-by-hashtag listing by the post
        // module, which is why POSTS is declared as an absolute path rather than as a suffix.
        assertThat(fieldNames(ApiConstants.Hashtags.class)).contains("BY_NAME", "POSTS");

        assertThat(routedPaths(HashtagController.class)).contains(ApiConstants.Hashtags.BY_NAME);
        assertThat(routedPaths(HashtagPostController.class)).contains(ApiConstants.Hashtags.POSTS);
    }

    private static List<String> routedPaths(Class<?> controller) {
        return Arrays.stream(controller.getDeclaredMethods())
                .map(m -> m.getAnnotation(GetMapping.class))
                .filter(a -> a != null)
                .flatMap(a -> Arrays.stream(a.value()))
                .toList();
    }

    private static List<String> postRoutedPaths(Class<?> controller) {
        return Arrays.stream(controller.getDeclaredMethods())
                .map(m -> m.getAnnotation(PostMapping.class))
                .filter(a -> a != null)
                .flatMap(a -> Arrays.stream(a.value()))
                .toList();
    }

    private static List<String> deleteRoutedPaths(Class<?> controller) {
        return Arrays.stream(controller.getDeclaredMethods())
                .map(m -> m.getAnnotation(DeleteMapping.class))
                .filter(a -> a != null)
                .flatMap(a -> Arrays.stream(a.value()))
                .toList();
    }

    private static List<String> fieldNames(Class<?> type) {
        return Arrays.stream(type.getDeclaredFields()).map(Field::getName).toList();
    }
}
