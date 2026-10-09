package com.app.modules.social.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.app.modules.social.entity.HiddenWord;

@Repository
public interface HiddenWordRepository extends JpaRepository<HiddenWord, UUID> {}
