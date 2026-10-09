package com.app.modules.social.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.app.modules.social.entity.InteractionLimit;

@Repository
public interface InteractionLimitRepository extends JpaRepository<InteractionLimit, UUID> {}
