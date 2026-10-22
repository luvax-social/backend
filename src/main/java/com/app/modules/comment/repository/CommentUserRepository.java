package com.app.modules.comment.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import com.app.modules.users.entity.User;

/**
 * Module-local read-only repository over the users module's {@link User} entity.
 *
 * <p>Mirrors the {@code PostUserRepository} precedent: cross-module data is read through a
 * repository owned by this module instead of injecting another module's repository bean. Used to
 * resolve {@code @mention} usernames to user ids.
 */
@org.springframework.stereotype.Repository
public interface CommentUserRepository extends Repository<User, UUID> {

    /**
     * Finds the active account holding a username, in any casing.
     *
     * <p>The same case-insensitive match the users module uses everywhere a username is looked up.
     * A derived query here compared case-sensitively, so a mention typed {@code @Jane.Doe} resolved
     * to nobody while {@code @jane.doe} resolved to the account.
     *
     * @param username username in any casing
     * @return the matching active user, or empty when no active account holds that name
     */
    @Query(
            "SELECT u FROM User u WHERE lower(u.username) = lower(:username) AND u.deletedAt IS NULL")
    Optional<User> findByUsernameAndDeletedAtIsNull(@Param("username") String username);
}
