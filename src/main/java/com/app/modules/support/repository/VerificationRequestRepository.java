package com.app.modules.support.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.app.modules.support.entity.VerificationRequest;

@Repository
public interface VerificationRequestRepository extends JpaRepository<VerificationRequest, UUID> {}
