package com.app.modules.support.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import com.app.modules.support.entity.VerificationCategory;

@Repository
public interface VerificationCategoryRepository
        extends JpaRepository<VerificationCategory, String> {

    /**
     * The categories a requester may choose from, in display order.
     *
     * <p>Disabled rows are excluded here, unlike the vocabulary surface which includes them: a
     * client rendering a chooser must not offer a category the platform has withdrawn, while a
     * client rendering an existing grant still has to name its category.
     *
     * @return enabled categories ordered by {@code sortOrder}
     */
    @Query("SELECT c FROM VerificationCategory c WHERE c.enabled = true ORDER BY c.sortOrder ASC")
    List<VerificationCategory> findSelectable();
}
