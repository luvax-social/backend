package com.app.common.seed.writer;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.app.common.seed.loader.SeedContent;
import com.app.common.seed.model.ConversationSeed;
import com.app.common.seed.model.MediaManifestEntry;
import com.app.common.seed.model.MessageSeed;
import com.app.common.seed.model.PostSeed;
import com.app.common.seed.model.UserSeed;
import com.app.modules.media.enums.MediaType;
import com.app.modules.media.storage.MediaStorageKeyGenerator;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Seeds {@code media_assets} from {@code media_manifest.json}.
 *
 * <p>The manifest records one physical R2 object per entry (the {@code
 * seed/library/{manifest_id}.{ext}} key uploaded by the media provisioning script), but every owner
 * that references that entry - a post author, or a user via {@code banner_media_ref} - needs its
 * own {@code media_assets} row, because {@code storage_key} is globally unique and the production
 * write path never lets two owners share a key. Avatars are the one exception: {@code
 * users.avatar_url} is a literal externally-hosted URL {@link UserSeedWriter} writes directly from
 * {@code users.json}'s {@code avatar_url} field, never touching R2 or this table, so this writer
 * only ever collects banner uses. This writer therefore inserts one row per (manifest entry, owner)
 * <b>use</b>, minting a fresh {@code users/{ownerId}/media/{randomUUID}.{ext}}-shaped key via
 * {@link MediaStorageKeyGenerator} for every row while reusing the manifest entry's single real
 * {@code cdn_url}, so every reader gets a working image without a duplicate upload.
 *
 * <p><b>Composite-key convention</b>: the returned map is keyed by {@code manifestId + "::" +
 * ownerUserId} - the manifest entry's id, then the owner's generated {@code users.id} rendered via
 * {@link UUID#toString()} - not by manifest id alone, because one manifest id can back multiple
 * {@code media_assets} rows (one per distinct owner). A downstream writer resolving a post's media
 * must build this same key from the post's author id it already holds: {@code mediaRef + "::" +
 * authorUserId}.
 *
 * <p><b>{@code created_at}</b>: every row is stamped with its owner's own {@code users.created_at}
 * (read back from the DB, the same way {@link SocialGraphSeedWriter} reads it back, rather than
 * drawing a fresh value from {@link SeedTimeline}'s shared {@code Random} stream - a second draw
 * for the same user would return a different instant than the one {@link UserSeedWriter} already
 * persisted). Left unset, the column would default to the wall-clock moment the seed run executes,
 * which breaks the historical-window realism every other seeded table follows and inverts the
 * production relationship where an upload always precedes the content that references it.
 */
@Slf4j
@Service
@Profile("seed & (dev | prod)")
@RequiredArgsConstructor
public class MediaSeedWriter {

    private static final String INSERT_MEDIA_SQL =
            "INSERT INTO media_assets (id, user_id, storage_key, cdn_url, media_type, mime_type,"
                    + " file_size, width, height, duration, created_at) VALUES (?, ?, ?, ?,"
                    + " ?::media_type, ?, ?, ?, ?, ?, ?)";

    private final JdbcTemplate jdbc;
    private final MediaStorageKeyGenerator storageKeyGenerator;

    /**
     * Inserts one {@code media_assets} row per (manifest entry, owner) use referenced by {@code
     * users.json} (banner) and {@code posts.json} (post media), and returns the composite-key map
     * documented on this class.
     *
     * @param usersByUsername username-to-id map produced by {@link UserSeedWriter#write}
     * @return a map from {@code manifestId + "::" + ownerUserId} (owner id rendered via {@link
     *     UUID#toString()}) to the generated {@code media_assets.id}
     */
    public Map<String, UUID> write(SeedContent content, Map<String, UUID> usersByUsername) {
        Map<String, MediaManifestEntry> manifestById = new HashMap<>();
        for (MediaManifestEntry entry : content.mediaManifest()) {
            manifestById.put(entry.id(), entry);
        }

        // LinkedHashMap dedupes repeated (manifestId, ownerUsername) uses to exactly one row while
        // keeping insertion order deterministic for the batch insert below.
        Map<String, Use> usesByDedupeKey = new LinkedHashMap<>();
        collectUserBannerUses(content, usesByDedupeKey);
        collectPostMediaUses(content, usesByDedupeKey);
        collectMessageMediaUses(content, usesByDedupeKey);

        Map<UUID, Instant> createdAtByUserId = fetchCreatedAtByUserId();

        Map<String, UUID> mediaIdByCompositeKey = new HashMap<>();
        List<Object[]> rows = new ArrayList<>();
        for (Use use : usesByDedupeKey.values()) {
            MediaManifestEntry manifest = manifestById.get(use.manifestId());
            UUID ownerUserId = usersByUsername.get(use.ownerUsername());
            if (manifest == null || ownerUserId == null) {
                throw new IllegalStateException(
                        "MediaSeedWriter: unresolved reference for manifest '"
                                + use.manifestId()
                                + "', owner '"
                                + use.ownerUsername()
                                + "' (manifest present: "
                                + (manifest != null)
                                + ", owner present: "
                                + (ownerUserId != null)
                                + ")");
            }
            Instant ownerCreatedAt = createdAtByUserId.get(ownerUserId);
            if (ownerCreatedAt == null) {
                throw new IllegalStateException(
                        "MediaSeedWriter: no persisted users.created_at found for owner '"
                                + use.ownerUsername()
                                + "' (id "
                                + ownerUserId
                                + ") - UserSeedWriter must run before MediaSeedWriter");
            }

            UUID mediaAssetId = UUID.randomUUID();
            MediaType mediaType =
                    "video".equals(manifest.kind()) ? MediaType.VIDEO : MediaType.IMAGE;
            String storageKey =
                    storageKeyGenerator.generate(ownerUserId, mediaType, manifest.mimeType());

            rows.add(
                    new Object[] {
                        mediaAssetId,
                        ownerUserId,
                        storageKey,
                        manifest.cdnUrl(),
                        manifest.kind(),
                        manifest.mimeType(),
                        manifest.fileSizeBytes(),
                        manifest.width(),
                        manifest.height(),
                        "video".equals(manifest.kind()) ? manifest.durationSeconds() : null,
                        Timestamp.from(ownerCreatedAt)
                    });
            mediaIdByCompositeKey.put(compositeKey(use.manifestId(), ownerUserId), mediaAssetId);
        }

        jdbc.batchUpdate(INSERT_MEDIA_SQL, rows, rows.size(), this::bindMediaRow);

        log.info("[seed] media_assets: {} rows written", rows.size());
        return mediaIdByCompositeKey;
    }

    // Read back from the DB rather than recomputing via SeedTimeline.userCreatedAt(UserSeed): that
    // method draws from SeedTimeline's shared Random stream, so calling it again here would advance
    // the stream and return a value different from what UserSeedWriter already persisted. Querying
    // the row UserSeedWriter wrote is the only way to get the true value (same technique
    // SocialGraphSeedWriter uses for the same reason).
    private Map<UUID, Instant> fetchCreatedAtByUserId() {
        Map<UUID, Instant> createdAtByUserId = new HashMap<>();
        jdbc.query(
                "SELECT id, created_at FROM users",
                rs -> {
                    UUID id = (UUID) rs.getObject("id");
                    Instant createdAt = rs.getTimestamp("created_at").toInstant();
                    createdAtByUserId.put(id, createdAt);
                });
        return createdAtByUserId;
    }

    // Avatars no longer flow through this method - users.avatar_url is a literal external URL
    // UserSeedWriter writes straight from users.json, so only banner_media_ref ever needs a
    // media_assets row here.
    private void collectUserBannerUses(SeedContent content, Map<String, Use> uses) {
        for (UserSeed user : content.users()) {
            if (user.bannerMediaRef() != null) {
                putUse(uses, user.bannerMediaRef(), user.username());
            }
        }
    }

    private void collectPostMediaUses(SeedContent content, Map<String, Use> uses) {
        for (PostSeed post : content.posts()) {
            for (String mediaRef : post.mediaRefs()) {
                putUse(uses, mediaRef, post.authorUsername());
            }
        }
    }

    // An image/video message's media_ref becomes a use owned by its sender, the same way a post's
    // media_refs are owned by the post's author - a DM attachment is an upload the sender made, not
    // one shared from an existing media_assets row.
    private void collectMessageMediaUses(SeedContent content, Map<String, Use> uses) {
        for (ConversationSeed conversation : content.conversations()) {
            for (MessageSeed message : conversation.messages()) {
                if (message.mediaRef() != null) {
                    putUse(uses, message.mediaRef(), message.sender());
                }
            }
        }
    }

    private void putUse(Map<String, Use> uses, String manifestId, String ownerUsername) {
        String dedupeKey = manifestId + "::" + ownerUsername;
        uses.putIfAbsent(dedupeKey, new Use(manifestId, ownerUsername));
    }

    /**
     * Builds the same composite key this writer's returned map is keyed by, so a downstream writer
     * holding a post's author id (and not this writer's internal username) can look up the {@code
     * media_assets.id} for a given manifest entry.
     */
    static String compositeKey(String manifestId, UUID ownerUserId) {
        return manifestId + "::" + ownerUserId;
    }

    private void bindMediaRow(PreparedStatement ps, Object[] row) throws SQLException {
        ps.setObject(1, row[0]);
        ps.setObject(2, row[1]);
        ps.setString(3, (String) row[2]);
        ps.setString(4, (String) row[3]);
        ps.setString(5, (String) row[4]);
        ps.setString(6, (String) row[5]);
        ps.setLong(7, (Long) row[6]);
        ps.setInt(8, (Integer) row[7]);
        ps.setInt(9, (Integer) row[8]);
        if (row[9] == null) {
            ps.setNull(10, Types.INTEGER);
        } else {
            ps.setInt(10, (Integer) row[9]);
        }
        ps.setTimestamp(11, (Timestamp) row[10]);
    }

    private record Use(String manifestId, String ownerUsername) {}
}
