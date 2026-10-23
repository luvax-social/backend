package com.app.modules.post.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import com.app.modules.users.entity.User;

/**
 * Module-local read-only repository over the users module's {@link User} entity.
 *
 * <p>Mirrors the {@code SocialUserRepository} precedent: cross-module data is read through a
 * repository owned by this module instead of injecting another module's repository bean.
 */
@org.springframework.stereotype.Repository
public interface PostUserRepository extends Repository<User, UUID> {

    Optional<User> findByIdAndDeletedAtIsNull(UUID id);

    boolean existsByIdAndDeletedAtIsNull(UUID id);

    List<User> findAllByIdInAndDeletedAtIsNull(Collection<UUID> ids);

    /**
     * Finds the active account holding a username, in any casing; the match mentions resolve by.
     *
     * @param username username in any casing
     * @return the matching active user, or empty when no active account holds that name
     */
    @Query(
            "SELECT u FROM User u WHERE lower(u.username) = lower(:username) AND u.deletedAt IS NULL")
    Optional<User> findActiveByUsername(@Param("username") String username);
}
