package com.app.common.seed.writer;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.app.common.seed.loader.SeedContent;
import com.app.common.seed.model.MediaManifestEntry;
import com.app.common.seed.model.UserSeed;
import com.app.common.seed.time.SeedTimeline;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Seeds the accounts declared in {@code users.json}: one {@code users} row, one {@code
 * user_credentials} row, and one {@code user_settings} row per user, matching the invariant that
 * every account carries exactly one settings row from creation. The settings row carries the
 * account's {@code message_permission} and its {@code hidden_words_system_enabled} choice, which
 * {@code users.json} lists only for the accounts that differ from the defaults.
 */
@Slf4j
@Service
@Profile("seed & (dev | prod)")
@RequiredArgsConstructor
public class UserSeedWriter {

    private static final String INSERT_USER_SQL =
            "INSERT INTO users (id, username, email, display_name, bio, role, status, is_private,"
                    + " is_verified, created_at, avatar_url, banner_url) VALUES (?, ?, ?, ?, ?,"
                    + " ?::user_role, ?::user_status, ?, ?, ?, ?, ?)";

    private static final String INSERT_CREDENTIALS_SQL =
            "INSERT INTO user_credentials (user_id, password_hash, email_verified,"
                    + " email_verified_at, created_at) VALUES (?, ?, ?, ?, ?)";

    private static final String INSERT_SETTINGS_SQL =
            "INSERT INTO user_settings (user_id, updated_at, message_permission,"
                    + " hidden_words_system_enabled) VALUES (?, ?, ?::message_permission, ?)";

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;

    /**
     * Inserts every user from {@code users.json} and returns the username-to-generated-id mapping
     * every later seed writer resolves author/participant/actor references through.
     *
     * <p>Every seeded account shares the plaintext password {@code Password123!} (per {@code
     * users.json}'s {@code _password_note}), so the {@link PasswordEncoder#encode(CharSequence)}
     * call happens exactly once and the resulting BCrypt hash is reused verbatim for every row;
     * {@code PasswordEncoder.matches()} re-derives the salt from the stored hash itself, so one
     * fixed hash verifies correctly against the shared plaintext for every row.
     *
     * @return an immutable-by-convention map from {@code username} to the generated {@code
     *     users.id}, one entry per user in {@code content.users()}
     */
    public Map<String, UUID> write(SeedContent content, SeedTimeline timeline) {
        List<UserSeed> users = content.users();
        String sharedPasswordHash = passwordEncoder.encode("Password123!");
        Map<String, String> cdnUrlByManifestId = cdnUrlByManifestId(content);

        Map<String, UUID> usersByUsername = new HashMap<>();
        List<Object[]> userRows = new ArrayList<>();
        List<Object[]> credentialRows = new ArrayList<>();
        List<Object[]> settingsRows = new ArrayList<>();

        for (UserSeed user : users) {
            UUID userId = UUID.randomUUID();
            Instant createdAt = timeline.userCreatedAt(user);
            usersByUsername.put(user.username(), userId);
            // bannerMediaRef resolves against the static manifest content already loaded, not
            // against media_assets - MediaSeedWriter (which mints the actual media_assets row for
            // this same manifest entry) runs after UserSeedWriter and reads users.created_at back
            // from the DB, so resolving here avoids an extra UPDATE pass after that writer runs.
            String bannerUrl =
                    user.bannerMediaRef() == null
                            ? null
                            : cdnUrlByManifestId.get(user.bannerMediaRef());

            userRows.add(
                    new Object[] {
                        userId,
                        user.username(),
                        user.email(),
                        user.displayName(),
                        user.bio(),
                        user.role(),
                        user.status(),
                        user.isPrivate(),
                        false,
                        Timestamp.from(createdAt),
                        user.avatarUrl(),
                        bannerUrl
                    });
            credentialRows.add(
                    new Object[] {
                        userId,
                        sharedPasswordHash,
                        user.emailVerified(),
                        user.emailVerified() ? Timestamp.from(createdAt) : null,
                        Timestamp.from(createdAt)
                    });
            settingsRows.add(
                    new Object[] {
                        userId,
                        Timestamp.from(createdAt),
                        user.messagePermission(),
                        user.hiddenWordsSystemEnabled()
                    });
        }

        jdbc.batchUpdate(INSERT_USER_SQL, userRows, userRows.size(), this::bindUserRow);
        jdbc.batchUpdate(
                INSERT_CREDENTIALS_SQL,
                credentialRows,
                credentialRows.size(),
                this::bindCredentialRow);
        jdbc.batchUpdate(
                INSERT_SETTINGS_SQL, settingsRows, settingsRows.size(), this::bindSettingsRow);

        log.info("[seed] users: {} rows written", usersByUsername.size());
        return usersByUsername;
    }

    private void bindUserRow(PreparedStatement ps, Object[] row) throws SQLException {
        ps.setObject(1, row[0]);
        ps.setString(2, (String) row[1]);
        ps.setString(3, (String) row[2]);
        ps.setString(4, (String) row[3]);
        ps.setString(5, (String) row[4]);
        ps.setString(6, (String) row[5]);
        ps.setString(7, (String) row[6]);
        ps.setBoolean(8, (Boolean) row[7]);
        ps.setBoolean(9, (Boolean) row[8]);
        ps.setTimestamp(10, (Timestamp) row[9]);
        ps.setString(11, (String) row[10]);
        if (row[11] == null) {
            ps.setNull(12, Types.VARCHAR);
        } else {
            ps.setString(12, (String) row[11]);
        }
    }

    // MediaManifestEntry.cdnUrl is the single real R2 URL every reader shares for that manifest
    // entry (see media_manifest.json's _storage_key_strategy) - resolving banner_url against this
    // map, not against a media_assets row, is what lets this writer stay a single INSERT per table
    // instead of depending on MediaSeedWriter having already run.
    private Map<String, String> cdnUrlByManifestId(SeedContent content) {
        Map<String, String> cdnUrlByManifestId = new HashMap<>();
        for (MediaManifestEntry entry : content.mediaManifest()) {
            cdnUrlByManifestId.put(entry.id(), entry.cdnUrl());
        }
        return cdnUrlByManifestId;
    }

    private void bindCredentialRow(PreparedStatement ps, Object[] row) throws SQLException {
        ps.setObject(1, row[0]);
        ps.setString(2, (String) row[1]);
        ps.setBoolean(3, (Boolean) row[2]);
        if (row[3] == null) {
            ps.setNull(4, Types.TIMESTAMP_WITH_TIMEZONE);
        } else {
            ps.setTimestamp(4, (Timestamp) row[3]);
        }
        ps.setTimestamp(5, (Timestamp) row[4]);
    }

    private void bindSettingsRow(PreparedStatement ps, Object[] row) throws SQLException {
        ps.setObject(1, row[0]);
        ps.setTimestamp(2, (Timestamp) row[1]);
        ps.setString(3, (String) row[2]);
        ps.setBoolean(4, (Boolean) row[3]);
    }
}
