package com.app.modules.admin.service.impl;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.app.common.enums.ApiErrorCode;
import com.app.common.exception.AppException;
import com.app.modules.admin.dto.request.AdminCreateHashtagRequest;
import com.app.modules.admin.dto.request.AdminDeleteHashtagRequest;
import com.app.modules.admin.dto.request.AdminUpdateHashtagRequest;
import com.app.modules.admin.service.AdminActionRecorder;
import com.app.modules.admin.service.AdminAuthorizationService;
import com.app.modules.hashtag.enums.HashtagStatus;
import com.app.modules.hashtag.service.HashtagLifecycleService;

/**
 * Pins the second authorization gate this service is documented to apply.
 *
 * <p>{@code AdminHashtagController} states that {@code assertActorIsAdministrator} is called by
 * every method here, so that deleting the controller's class-level {@code @PreAuthorize} does not
 * open the endpoints. That was true of five of the seven methods: {@code pinHashtag} and {@code
 * unpinHashtag} were added later against the earlier shape and asserted nothing, leaving the
 * annotation as the only thing between a moderator and the platform-wide pin controls.
 *
 * <p>Written as a dynamic test over every method rather than one test per method on purpose: an
 * eighth method added without the gate fails this class without anyone remembering to extend it.
 */
@ExtendWith(MockitoExtension.class)
class AdminHashtagServiceImplTest {

    private static final UUID ACTOR_ID = UUID.randomUUID();
    private static final UUID HASHTAG_ID = UUID.randomUUID();

    @Mock private HashtagLifecycleService hashtagLifecycleService;
    @Mock private AdminActionRecorder adminActionRecorder;
    @Mock private AdminAuthorizationService adminAuthorizationService;

    private AdminHashtagServiceImpl service;

    @BeforeEach
    void setUp() {
        service =
                new AdminHashtagServiceImpl(
                        hashtagLifecycleService, adminActionRecorder, adminAuthorizationService);
        lenient()
                .doThrow(new AppException(ApiErrorCode.FORBIDDEN))
                .when(adminAuthorizationService)
                .assertActorIsAdministrator(eq(ACTOR_ID));
    }

    @TestFactory
    List<DynamicTest> everyMethodRefusesAnActorWhoIsNotAnAdministrator() {
        record Call(String name, Consumer<AdminHashtagServiceImpl> invoke) {}
        List<Call> calls =
                List.of(
                        new Call("listHashtags", s -> s.listHashtags(ACTOR_ID, null, null, 20)),
                        new Call(
                                "searchHashtags",
                                s -> s.searchHashtags(ACTOR_ID, "q", null, null, 20)),
                        new Call(
                                "createHashtag",
                                s ->
                                        s.createHashtag(
                                                ACTOR_ID,
                                                new AdminCreateHashtagRequest(
                                                        "devlife", HashtagStatus.ACTIVE, null))),
                        new Call(
                                "updateHashtag",
                                s ->
                                        s.updateHashtag(
                                                ACTOR_ID,
                                                HASHTAG_ID,
                                                new AdminUpdateHashtagRequest(
                                                        HashtagStatus.ACTIVE, null))),
                        new Call(
                                "deleteHashtag",
                                s ->
                                        s.deleteHashtag(
                                                ACTOR_ID,
                                                HASHTAG_ID,
                                                new AdminDeleteHashtagRequest(null))),
                        new Call("pinHashtag", s -> s.pinHashtag(ACTOR_ID, HASHTAG_ID, null)),
                        new Call("unpinHashtag", s -> s.unpinHashtag(ACTOR_ID, HASHTAG_ID, null)));

        return calls.stream()
                .map(
                        call ->
                                DynamicTest.dynamicTest(
                                        call.name(),
                                        () -> {
                                            assertThatThrownBy(() -> call.invoke().accept(service))
                                                    .isInstanceOf(AppException.class);
                                            // The gate must run before any work is done, not
                                            // merely somewhere inside the method.
                                            verifyNoInteractions(hashtagLifecycleService);
                                            verifyNoInteractions(adminActionRecorder);
                                        }))
                .toList();
    }
}
