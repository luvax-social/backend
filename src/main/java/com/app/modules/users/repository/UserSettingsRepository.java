package com.app.modules.users.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.app.modules.users.entity.UserSettings;

@Repository
public interface UserSettingsRepository extends JpaRepository<UserSettings, UUID> {}
