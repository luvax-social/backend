package com.app.modules.users.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import com.app.modules.users.converter.UserRoleConverter;
import com.app.modules.users.converter.UserStatusConverter;
import com.app.modules.users.enums.UserRole;
import com.app.modules.users.enums.UserStatus;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Aggregate root for an end user.
 *
 * <p>Maps to the {@code users} table. The denormalized counter columns ({@code follower_count},
 * {@code following_count}, {@code post_count}) are maintained by Postgres triggers (V16) and are
 * intentionally marked {@code insertable = false, updatable = false}.
 */
@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "username", nullable = false, unique = true, length = 30)
    private String username;

    @Column(name = "email", nullable = false, unique = true, length = 255)
    private String email;

    @Column(name = "display_name", length = 100)
    private String displayName;

    @Column(name = "bio", columnDefinition = "TEXT")
    private String bio;

    @Column(name = "avatar_url", columnDefinition = "TEXT")
    private String avatarUrl;

    @Column(name = "banner_url", columnDefinition = "TEXT")
    private String bannerUrl;

    @Column(name = "website_url", columnDefinition = "TEXT")
    private String websiteUrl;

    @Convert(converter = UserRoleConverter.class)
    @Column(name = "role", nullable = false, columnDefinition = "user_role")
    private UserRole role;

    @Convert(converter = UserStatusConverter.class)
    @Column(name = "status", nullable = false, columnDefinition = "user_status")
    private UserStatus status;

    @Column(name = "is_private", nullable = false)
    private boolean isPrivate;

    @Column(name = "is_verified", nullable = false)
    private boolean isVerified;

    /**
     * The category of the account's active verified badge, or null when it holds none.
     *
     * <p>Denormalised from {@code user_verifications} by {@code trg_user_verification_sync} and
     * never written by application code, exactly as the counter columns are never written by it.
     * {@code insertable = false, updatable = false} makes that structural rather than a convention
     * somebody has to remember: a service cannot set this even by accident.
     */
    @Column(name = "verified_category", length = 50, insertable = false, updatable = false)
    private String verifiedCategory;

    /** Client IP captured at account creation; null for every account created before V56. */
    @Column(name = "registration_ip", columnDefinition = "inet")
    private String registrationIp;

    /** Client IP of the most recent real login; not advanced by a token refresh. */
    @Column(name = "last_login_ip", columnDefinition = "inet")
    private String lastLoginIp;

    /** Timestamp of the most recent real login; not advanced by a token refresh. */
    @Column(name = "last_login_at")
    private OffsetDateTime lastLoginAt;

    /**
     * Moment a suspension lapses, or null for an indefinite one.
     *
     * <p>Meaningful only while {@code status = 'suspended'}. {@code status} remains authoritative
     * for the authorization decision on any request; this column decides only when a suspended
     * status ends, and every path that leaves the suspended state clears it.
     */
    @Column(name = "suspended_until")
    private OffsetDateTime suspendedUntil;

    /**
     * Monotonic counter that invalidates every access token minted before its current value.
     *
     * <p>Stamped into each access token at issuance and compared against this column on every
     * authenticated request. Incrementing it ends the target's access capability at once, which is
     * what force logout and role change need and what refresh-token revocation alone cannot give.
     *
     * <p>Never written through this entity. The sole writer is {@code
     * AdminUserRepository.incrementTokenEpoch}, whose atomic {@code token_epoch + 1} cannot lose an
     * increment to a concurrent one the way a read-modify-write through a managed entity could.
     */
    @Generated(event = EventType.INSERT)
    @Column(name = "token_epoch", nullable = false, insertable = false, updatable = false)
    private int tokenEpoch;

    /** Maintained exclusively by Postgres trigger {@code trg_follow_counts} (V16). */
    @Column(name = "follower_count", insertable = false, updatable = false)
    private int followerCount;

    /** Maintained exclusively by Postgres trigger {@code trg_follow_counts} (V16). */
    @Column(name = "following_count", insertable = false, updatable = false)
    private int followingCount;

    /** Maintained exclusively by Postgres trigger {@code trg_post_count} (V16). */
    @Column(name = "post_count", insertable = false, updatable = false)
    private int postCount;

    // Database-generated like updated_at, rather than @CreationTimestamp. The column's DEFAULT
    // NOW() and the sibling updated_at default both resolve to the same transaction timestamp, so
    // the two agree exactly at insert. Under @CreationTimestamp this value came from the JVM
    // clock while updated_at came from Postgres, leaving them permanently unequal on a user
    // nobody had touched. Hibernate already re-reads updated_at after every insert, so reading
    // this one back costs no extra round trip.
    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    // trg_users_updated_at (V16) is the sole writer of this column; Hibernate never sends it in an
    // INSERT or UPDATE and instead re-selects it afterward so the entity reflects the
    // trigger-written value instead of a stale application-side guess the trigger would discard.
    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;
}
