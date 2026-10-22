package com.app.modules.post.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.app.modules.post.entity.PostMedia;

/** Reads and writes single {@code post_media} rows, for the edits that do not go through a post. */
@Repository
public interface PostMediaRepository extends JpaRepository<PostMedia, UUID> {

    /**
     * Finds one media item of a post.
     *
     * @param id the media item
     * @param postId the post it must belong to
     * @return the item when it belongs to the post
     */
    @Query("SELECT m FROM PostMedia m WHERE m.id = :id AND m.post.id = :postId")
    Optional<PostMedia> findByIdAndPostId(@Param("id") UUID id, @Param("postId") UUID postId);
}
