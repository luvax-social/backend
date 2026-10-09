package com.app.modules.social.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.app.modules.social.entity.AccountShowFewer;
import com.app.modules.social.entity.AccountShowFewerId;

@Repository
public interface AccountShowFewerRepository
        extends JpaRepository<AccountShowFewer, AccountShowFewerId> {}
