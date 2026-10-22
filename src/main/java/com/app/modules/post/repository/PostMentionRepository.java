package com.app.modules.post.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.app.modules.post.entity.PostMention;
import com.app.modules.post.entity.PostMentionId;

@Repository
public interface PostMentionRepository extends JpaRepository<PostMention, PostMentionId> {

    @Query("SELECT m FROM PostMention m WHERE m.id.postId = :postId")
    List<PostMention> findByPostId(@Param("postId") UUID postId);

    @Query("SELECT m FROM PostMention m WHERE m.id.postId IN :postIds")
    List<PostMention> findByPostIdIn(@Param("postIds") Collection<UUID> postIds);

    @Modifying
    @Query("DELETE FROM PostMention m WHERE m.id.postId = :postId")
    int deleteByPostId(@Param("postId") UUID postId);
}
