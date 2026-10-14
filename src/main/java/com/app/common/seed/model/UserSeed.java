package com.app.common.seed.model;

public record UserSeed(
        String username,
        String displayName,
        String email,
        String bio,
        String personaId,
        String role,
        String status,
        boolean isPrivate,
        boolean emailVerified,
        int createdAtOffsetDays,
        String avatarUrl,
        String bannerMediaRef,
        int postCountTarget,
        boolean isQaAccount,
        String qaNote,
        String messagePermission,
        Boolean hiddenWordsSystemEnabled) {

    // An account that names neither setting keeps the schema defaults, so users.json only lists
    // the accounts that deviate from them.
    public UserSeed {
        messagePermission = messagePermission == null ? "everyone" : messagePermission;
        hiddenWordsSystemEnabled =
                hiddenWordsSystemEnabled == null ? Boolean.TRUE : hiddenWordsSystemEnabled;
    }
}
