package com.app.common.vocabulary.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import com.app.common.vocabulary.dto.response.SupportCategoryVocabularyResponse;
import com.app.common.vocabulary.repository.VocabularyRepository;

class VocabularyServiceImplTest {

    private VocabularyRepository repository;
    private VocabularyServiceImpl service;

    @BeforeEach
    void setUp() {
        repository = mock(VocabularyRepository.class);
        service = new VocabularyServiceImpl(repository, Duration.ZERO);
    }

    @Test
    void allowsPublicForm_nullKey_returnsFalseWithoutReadingRepository() {
        assertThat(service.allowsPublicForm(null)).isFalse();
        verifyNoInteractions(repository);
    }

    @Test
    void allowsPublicForm_enabledPublicCategory_matchesCaseInsensitively() {
        when(repository.findSupportCategories()).thenReturn(List.of(publicCategory("account")));

        assertThat(service.allowsPublicForm("ACCOUNT")).isTrue();
    }

    @Test
    void allowsPublicForm_disabledCategory_returnsFalse() {
        when(repository.findSupportCategories()).thenReturn(List.of(disabledCategory("account")));

        assertThat(service.allowsPublicForm("account")).isFalse();
    }

    @Test
    void allowsPublicForm_entryPoint_runsInReadOnlyTransaction() throws NoSuchMethodException {
        Transactional transactional =
                VocabularyServiceImpl.class
                        .getMethod("allowsPublicForm", String.class)
                        .getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.readOnly()).isTrue();
    }

    private static SupportCategoryVocabularyResponse publicCategory(String key) {
        return new SupportCategoryVocabularyResponse(key, key, null, false, true, true, (short) 1);
    }

    private static SupportCategoryVocabularyResponse disabledCategory(String key) {
        return new SupportCategoryVocabularyResponse(key, key, null, false, true, false, (short) 1);
    }
}
