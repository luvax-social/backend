package com.app.modules.social.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.app.modules.social.entity.UserMute;
import com.app.modules.social.entity.UserMuteId;

@Repository
public interface UserMuteRepository extends JpaRepository<UserMute, UserMuteId> {}
