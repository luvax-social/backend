package com.app.modules.hashtag.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.app.common.enums.ApiErrorCode;
import com.app.common.exception.AppException;
import com.app.modules.hashtag.entity.Hashtag;
import com.app.modules.hashtag.entity.PostHashtag;
import com.app.modules.hashtag.entity.PostHashtagId;
import com.app.modules.hashtag.repository.HashtagIndexProjection;
import com.app.modules.hashtag.repository.HashtagRepository;
import com.app.modules.hashtag.repository.PostHashtagNameProjection;
import com.app.modules.hashtag.repository.PostHashtagRepository;
import com.app.modules.hashtag.service.HashtagIndexEventPublisher;

@ExtendWith(MockitoExtension.class)
class HashtagServiceImplTest {

    @Mock private HashtagRepository hashtagRepository;
    @Mock private PostHashtagRepository postHashtagRepository;
    @Mock private HashtagIndexEventPublisher indexEventPublisher;
    @Mock private EntityManager entityManager;

    private HashtagServiceImpl service;

    @BeforeEach
    void setUp() {
        service =
                new HashtagServiceImpl(
                        hashtagRepository,
                        postHashtagRepository,
                        entityManager,
                        indexEventPublisher);
    }

    @Test
    void normalize_variousInputs_stripsHashLowercasesTrims() {
        assertThat(service.normalize("#Spring ")).isEqualTo("spring");
        assertThat(service.normalize("  JAVA")).isEqualTo("java");
        assertThat(service.normalize("already")).isEqualTo("already");
        assertThat(service.normalize(" #Spring ")).isEqualTo("spring");
    }

    @Test
    void normalize_nameAtMaxLength_isKept() {
        String name = "a".repeat(100);
        assertThat(service.normalize("#" + name)).isEqualTo(name);
    }

    @Test
    void normalize_nameExceedingMaxLength_returnsEmpty() {
        assertThat(service.normalize("#" + "a".repeat(101))).isEmpty();
        assertThat(service.normalize("a".repeat(150))).isEmpty();
    }

    // U+20000 (CJK Unified Ideograph Extension B, first codepoint) is a supplementary-plane
    // letter: two UTF-16 code units ("𠀀") per codepoint, category Lo so toLowerCase is
    // a no-op. Used to prove the 100-character bound counts code points, not UTF-16 units.
    private static final String SUPPLEMENTARY_LETTER = "𠀀";

    @Test
    void normalize_supplementaryPlaneAtMaxCodePoints_isKept() {
        String name = SUPPLEMENTARY_LETTER.repeat(100);
        assertThat(name.codePointCount(0, name.length())).isEqualTo(100);

        assertThat(service.normalize("#" + name)).isEqualTo(name);
    }

    @Test
    void normalize_supplementaryPlaneExceedingMaxCodePoints_returnsEmpty() {
        String name = SUPPLEMENTARY_LETTER.repeat(101);
        assertThat(name.codePointCount(0, name.length())).isEqualTo(101);

        assertThat(service.normalize("#" + name)).isEmpty();
    }

    @Test
    void upsertHashtagsForPost_overlongTag_isSkipped() {
        UUID postId = UUID.randomUUID();
        Hashtag hashtag = Hashtag.builder().id(UUID.randomUUID()).name("spring").build();
        when(hashtagRepository.findByName("spring")).thenReturn(Optional.of(hashtag));

        service.upsertHashtagsForPost(postId, List.of("#Spring", "#" + "x".repeat(150)));

        verify(hashtagRepository, times(1)).upsertByName("spring");
        verify(hashtagRepository, times(1)).upsertByName(any());
        verify(postHashtagRepository, times(1)).save(any(PostHashtag.class));
    }

    @Test
    void upsertHashtagsForPost_caseDifferingDuplicates_insertsOnce() {
        UUID postId = UUID.randomUUID();
        Hashtag hashtag = Hashtag.builder().id(UUID.randomUUID()).name("spring").build();
        when(hashtagRepository.findByName("spring")).thenReturn(Optional.of(hashtag));

        service.upsertHashtagsForPost(postId, List.of("#Spring", "spring", "SPRING"));

        verify(hashtagRepository, times(1)).upsertByName("spring");
        verify(postHashtagRepository, times(1)).save(any(PostHashtag.class));
    }

    @Test
    void upsertHashtagsForPost_newTags_enqueuesUpsertEvent() {
        UUID postId = UUID.randomUUID();
        UUID hashtagId = UUID.randomUUID();
        Hashtag hashtag = Hashtag.builder().id(hashtagId).name("spring").build();
        when(hashtagRepository.findByName("spring")).thenReturn(Optional.of(hashtag));

        service.upsertHashtagsForPost(postId, List.of("#Spring"));

        verify(indexEventPublisher).enqueueSync(hashtagId);
    }

    @Test
    void removeHashtagsForPost_lastAssociation_enqueuesDeleteEvent() {
        UUID postId = UUID.randomUUID();
        UUID hashtagId = UUID.randomUUID();
        when(postHashtagRepository.findHashtagIdsByPostId(postId)).thenReturn(List.of(hashtagId));
        when(hashtagRepository.findIndexProjectionsByIdIn(List.of(hashtagId)))
                .thenReturn(List.of());

        service.removeHashtagsForPost(postId);

        verify(postHashtagRepository).deleteAllByPostId(postId);
        verify(indexEventPublisher).enqueueDelete(hashtagId);
    }

    @Test
    void removeHashtagsForPost_remainingAssociations_enqueuesUpsertEvent() {
        UUID postId = UUID.randomUUID();
        UUID hashtagId = UUID.randomUUID();
        when(postHashtagRepository.findHashtagIdsByPostId(postId)).thenReturn(List.of(hashtagId));
        HashtagIndexProjection projection = projection(hashtagId, 2);
        when(hashtagRepository.findIndexProjectionsByIdIn(List.of(hashtagId)))
                .thenReturn(List.of(projection));

        service.removeHashtagsForPost(postId);

        verify(postHashtagRepository).deleteAllByPostId(postId);
        verify(indexEventPublisher).enqueueSync(hashtagId);
    }

    @Test
    void getHashtagIdsForPosts_multiplePosts_groupsByPostId() {
        UUID postA = UUID.randomUUID();
        UUID postB = UUID.randomUUID();
        UUID tag1 = UUID.randomUUID();
        UUID tag2 = UUID.randomUUID();
        UUID tag3 = UUID.randomUUID();
        when(postHashtagRepository.findAllByIdPostIdIn(List.of(postA, postB)))
                .thenReturn(
                        List.of(
                                PostHashtag.builder().id(new PostHashtagId(postA, tag1)).build(),
                                PostHashtag.builder().id(new PostHashtagId(postA, tag2)).build(),
                                PostHashtag.builder().id(new PostHashtagId(postB, tag3)).build()));

        Map<UUID, List<UUID>> result = service.getHashtagIdsForPosts(List.of(postA, postB));

        assertThat(result).containsOnlyKeys(postA, postB);
        assertThat(result.get(postA)).containsExactlyInAnyOrder(tag1, tag2);
        assertThat(result.get(postB)).containsExactly(tag3);
    }

    @Test
    void getHashtagIdsForPosts_emptyInput_returnsEmptyMap() {
        assertThat(service.getHashtagIdsForPosts(List.of())).isEmpty();
        verifyNoInteractions(postHashtagRepository);
    }

    @Test
    void findBannedNames_mixedInput_returnsOnlyTheBannedSubsetNormalized() {
        when(hashtagRepository.findBannedNames(List.of("spring", "banned", "java")))
                .thenReturn(List.of("banned"));

        assertThat(service.findBannedNames(List.of("#Spring", "BANNED", " java ")))
                .containsExactly("banned");
    }

    @Test
    void findBannedNames_emptyInput_returnsEmptyWithoutQuerying() {
        assertThat(service.findBannedNames(List.of())).isEmpty();

        verifyNoInteractions(hashtagRepository);
    }

    @Test
    void findBannedNames_allTagsActive_returnsEmpty() {
        when(hashtagRepository.findBannedNames(List.of("spring", "java"))).thenReturn(List.of());

        assertThat(service.findBannedNames(List.of("spring", "java"))).isEmpty();
    }

    @Test
    void findBannedNames_blankAndOverLongTags_areDroppedBeforeQuerying() {
        when(hashtagRepository.findBannedNames(List.of("spring"))).thenReturn(List.of());

        assertThat(service.findBannedNames(List.of("#", "  ", "spring", "x".repeat(101))))
                .isEmpty();

        verify(hashtagRepository).findBannedNames(List.of("spring"));
    }

    @Test
    void upsertHashtagsForPost_bannedTag_throwsAndAssociatesNothing() {
        UUID postId = UUID.randomUUID();
        when(hashtagRepository.findBannedNames(List.of("spring", "banned")))
                .thenReturn(List.of("banned"));

        assertThatThrownBy(
                        () -> service.upsertHashtagsForPost(postId, List.of("#spring", "#banned")))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.POST_BANNED_HASHTAG);

        verify(hashtagRepository, never()).upsertByName(any());
        verifyNoInteractions(postHashtagRepository);
        verifyNoInteractions(indexEventPublisher);
    }

    @Test
    void upsertHashtagsForPost_bannedTag_carriesTheOffendingNamesOnTheFailure() {
        when(hashtagRepository.findBannedNames(List.of("one", "two"))).thenReturn(List.of("two"));

        AppException thrown =
                catchThrowableOfType(
                        AppException.class,
                        () ->
                                service.upsertHashtagsForPost(
                                        UUID.randomUUID(), List.of("#one", "#two")));

        assertThat(thrown.getDetails()).isEqualTo(Map.of("bannedTags", List.of("two")));
    }

    @Test
    void upsertHashtagsForPostSkippingBanned_bannedTag_associatesTheRestAndReportsTheSkipped() {
        UUID postId = UUID.randomUUID();
        UUID keptId = UUID.randomUUID();
        Hashtag kept = Hashtag.builder().id(keptId).name("spring").build();
        when(hashtagRepository.findBannedNames(List.of("spring", "banned")))
                .thenReturn(List.of("banned"));
        when(hashtagRepository.findByName("spring")).thenReturn(Optional.of(kept));

        assertThat(
                        service.upsertHashtagsForPostSkippingBanned(
                                postId, List.of("#spring", "#banned")))
                .containsExactly("banned");

        verify(hashtagRepository).upsertByName("spring");
        verify(hashtagRepository, never()).upsertByName("banned");
        verify(postHashtagRepository, times(1)).save(any(PostHashtag.class));
        verify(indexEventPublisher).enqueueSync(keptId);
    }

    @Test
    void getVisibleHashtagsForPosts_emptyInput_returnsEmptyWithoutQuerying() {
        assertThat(service.getVisibleHashtagsForPosts(List.of())).isEmpty();

        verifyNoInteractions(postHashtagRepository);
    }

    @Test
    void getHashtagNamesForPosts_emptyInput_returnsEmptyWithoutQuerying() {
        assertThat(service.getHashtagNamesForPosts(List.of())).isEmpty();

        verifyNoInteractions(hashtagRepository);
    }

    @Test
    void getHashtagNamesForPosts_rowsOfSeveralPosts_areGroupedPerPostKeepingTheirOrder() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        // Built before stubbing, because creating a mock inside a thenReturn argument list would
        // start a second stubbing while the first is unfinished.
        List<PostHashtagNameProjection> rows =
                List.of(named(first, "alpha"), named(second, "zeta"), named(first, "beta"));
        when(hashtagRepository.findNamesByPostIds(List.of(first, second))).thenReturn(rows);

        Map<UUID, List<String>> names = service.getHashtagNamesForPosts(List.of(first, second));

        assertThat(names)
                .containsEntry(first, List.of("alpha", "beta"))
                .containsEntry(second, List.of("zeta"));
    }

    private static PostHashtagNameProjection named(UUID postId, String name) {
        PostHashtagNameProjection row = org.mockito.Mockito.mock(PostHashtagNameProjection.class);
        when(row.getPostId()).thenReturn(postId);
        when(row.getName()).thenReturn(name);
        return row;
    }

    private static HashtagIndexProjection projection(UUID id, int postCount) {
        HashtagIndexProjection projection = org.mockito.Mockito.mock(HashtagIndexProjection.class);
        when(projection.getId()).thenReturn(id);
        when(projection.getPostCount()).thenReturn(postCount);
        return projection;
    }
}
