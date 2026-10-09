package com.app.modules.post.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.app.modules.post.entity.PostNotInterested;
import com.app.modules.post.entity.PostNotInterestedId;

@Repository
public interface PostNotInterestedRepository
        extends JpaRepository<PostNotInterested, PostNotInterestedId> {}
