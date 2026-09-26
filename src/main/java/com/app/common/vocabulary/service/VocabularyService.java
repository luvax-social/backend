package com.app.common.vocabulary.service;

import com.app.common.vocabulary.dto.response.VocabularyResponse;

/** Read access to the display vocabularies behind the API's closed enum sets. */
public interface VocabularyService {

    /**
     * Returns every display vocabulary in one response.
     *
     * <p>Includes disabled rows. A client that never sees a disabled reason keeps offering it and
     * the user meets a rejection for something the interface said was valid, which is the failure
     * this endpoint exists to prevent.
     *
     * @return report reasons, notification types and moderation action types, each with its display
     *     metadata and its enabled flag
     */
    VocabularyResponse getVocabularies();

    /**
     * The support categories an anonymous submitter may choose from.
     *
     * <p>Narrower than {@link #getVocabularies()} in both directions: only support categories, and
     * only the rows that are enabled and permitted on the public form. Appeal categories are
     * excluded by that flag, which is correct - an appeal needs an audit row to appeal against,
     * which only a signed link supplies.
     *
     * @return enabled, public-form support categories in display order
     */
    java.util.List<com.app.common.vocabulary.dto.response.SupportCategoryVocabularyResponse>
            getPublicSupportCategories();

    /**
     * Whether the public form may carry this category.
     *
     * <p>The authority for that question. {@code support_category_configs} is where the rule lives,
     * because {@code allows_public_form} is behavioural policy rather than display metadata and a
     * policy with two readers has two answers. The submit path previously decided it from Java enum
     * properties instead, so disabling a category hid it from the form while the service kept
     * accepting it from any client that posted the key directly.
     *
     * <p>Reads the same cached rows the form is built from, so the advertised set and the accepted
     * set cannot disagree.
     *
     * @param categoryKey the category key a submission carries
     * @return true when the row exists and is both enabled and permitted on the public form
     */
    boolean allowsPublicForm(String categoryKey);
}
