package com.app.modules.comment.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.app.modules.comment.entity.CommentMention;
import com.app.modules.comment.entity.CommentMentionId;

@Repository
public interface CommentMentionRepository extends JpaRepository<CommentMention, CommentMentionId> {

    @Query("SELECT m FROM CommentMention m WHERE m.id.commentId IN :commentIds")
    List<CommentMention> findByCommentIdIn(@Param("commentIds") Collection<UUID> commentIds);

    @Modifying
    @Query("DELETE FROM CommentMention m WHERE m.id.commentId = :commentId")
    int deleteByCommentId(@Param("commentId") UUID commentId);
}
