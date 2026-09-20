package com.app.common.seed.loader;

import java.util.List;

import com.app.common.seed.model.BadgeSeed;
import com.app.common.seed.model.CommentPoolSeed;
import com.app.common.seed.model.ConversationSeed;
import com.app.common.seed.model.HashtagSeed;
import com.app.common.seed.model.MediaManifestEntry;
import com.app.common.seed.model.ModerationCaseSeed;
import com.app.common.seed.model.ModerationSupplementaryAction;
import com.app.common.seed.model.ModerationSupplementaryReport;
import com.app.common.seed.model.PersonaSeed;
import com.app.common.seed.model.PostSeed;
import com.app.common.seed.model.SupportTicketPoolSeed;
import com.app.common.seed.model.UserSeed;

/**
 * Aggregates every parsed seed JSON file into one immutable snapshot, handed to every domain writer
 * in Tasks 5-7.
 */
public record SeedContent(
        List<PersonaSeed> personas,
        List<UserSeed> users,
        List<HashtagSeed> hashtags,
        List<PostSeed> posts,
        CommentPoolSeed commentPools,
        List<ConversationSeed> conversations,
        List<ModerationCaseSeed> moderationCases,
        List<ModerationSupplementaryAction> supplementaryModerationActions,
        List<ModerationSupplementaryReport> supplementaryModerationReports,
        List<MediaManifestEntry> mediaManifest,
        SupportTicketPoolSeed supportTicketPools,
        BadgeSeed badges) {}
