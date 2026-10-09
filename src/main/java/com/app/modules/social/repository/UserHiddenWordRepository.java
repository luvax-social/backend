package com.app.modules.social.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.app.modules.social.entity.UserHiddenWord;

@Repository
public interface UserHiddenWordRepository extends JpaRepository<UserHiddenWord, UUID> {}
