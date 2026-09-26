package com.app.modules.mail.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.app.modules.mail.entity.EmailDelivery;

@Repository
public interface EmailDeliveryRepository extends JpaRepository<EmailDelivery, UUID> {}
