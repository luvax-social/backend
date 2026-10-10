package com.app.modules.comment.service.impl;

import java.util.UUID;

import org.springframework.stereotype.Service;

import com.app.common.enums.ApiErrorCode;
import com.app.common.exception.AppException;
import com.app.modules.comment.service.CommentAccessPolicyService;
import com.app.modules.post.entity.Post;
import com.app.modules.post.service.PostVisibilityService;

/**
 * Keeps only the post-visibility check for commenting, which is a read-visibility rule. The post
 * owner's block, interaction limit and Hidden Words are interaction rules and are evaluated by the
 * interaction policy in the comment write path.
 */
@Service
public class CommentAccessPolicyServiceImpl implements CommentAccessPolicyService {

    private final PostVisibilityService postVisibilityService;

    public CommentAccessPolicyServiceImpl(PostVisibilityService postVisibilityService) {
        this.postVisibilityService = postVisibilityService;
    }

    @Override
    public void assertCanComment(UUID viewerId, Post post) {
        if (!postVisibilityService.isVisibleTo(viewerId, post)) {
            throw new AppException(ApiErrorCode.POST_COMMENTING_RESTRICTED);
        }
    }
}
