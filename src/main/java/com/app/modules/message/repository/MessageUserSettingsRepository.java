package com.app.modules.message.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.app.modules.users.entity.UserSettings;

/**
 * Module-local read-only repository over the users module's {@link UserSettings} entity.
 *
 * <p>Used to read {@code message_permission} when gating a new direct conversation from a
 * non-follower.
 */
@Repository
public interface MessageUserSettingsRepository extends JpaRepository<UserSettings, UUID> {}
