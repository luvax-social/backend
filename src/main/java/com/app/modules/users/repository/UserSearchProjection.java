package com.app.modules.users.repository;

import java.util.UUID;

/**
 * One user search hit, projected straight from the search query.
 *
 * <p>Carries exactly the fields of the shared public summary and nothing more - no email, no role,
 * no account status. Projected rather than hydrated as entities so a full page of hits never loads
 * the {@code User} aggregate.
 */
public interface UserSearchProjection {

    UUID getId();

    String getUsername();

    String getDisplayName();

    String getAvatarUrl();

    boolean getIsVerified();

    /** Null exactly when {@link #getIsVerified()} is false. */
    String getVerifiedCategory();
}
