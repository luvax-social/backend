package com.app.modules.comment.service.impl;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.app.modules.comment.service.CommentModerationService;

@Service
public class CommentModerationServiceImpl implements CommentModerationService {

    private static final int MAX_LENGTH = 2200;
    private static final int MAX_TOKEN_REPEAT = 3;
    private static final int CAPS_SPAM_LENGTH = 50;

    @Override
    public ModerationResult check(String normalizedContent) {
        if (normalizedContent == null || normalizedContent.isBlank()) {
            return ModerationResult.rejected("empty");
        }
        if (normalizedContent.length() > MAX_LENGTH) {
            return ModerationResult.rejected("too_long");
        }
        if (isSpam(normalizedContent)) {
            return ModerationResult.rejected("spam");
        }
        return ModerationResult.approved();
    }

    private boolean isSpam(String content) {
        if (content.length() > CAPS_SPAM_LENGTH
                && content.equals(content.toUpperCase(Locale.ROOT))) {
            return true;
        }
        Map<String, Integer> counts = new HashMap<>();
        for (String token : List.of(content.toLowerCase(Locale.ROOT).split("\\s+"))) {
            if (token.isBlank()) {
                continue;
            }
            int next = counts.merge(token, 1, Integer::sum);
            if (next > MAX_TOKEN_REPEAT) {
                return true;
            }
        }
        return false;
    }
}
