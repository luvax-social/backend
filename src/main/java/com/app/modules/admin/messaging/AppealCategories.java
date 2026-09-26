package com.app.modules.admin.messaging;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.app.modules.admin.enums.AdminActionType;
import com.app.modules.support.enums.SupportCategory;

/**
 * Which appeal a moderation action can be contested under.
 *
 * <p>Only the six punitive actions are appealable. The two reinstating actions are not - there is
 * nothing to contest about being unbanned - and neither are the two support ticket notices, which
 * would otherwise let a rejected appeal be appealed in an unbounded loop.
 *
 * <p>The category is decided here rather than by the submitter, and travels inside the single-use
 * token. A client-supplied category would let someone appeal a decision the token never authorised.
 */
public final class AppealCategories {

    private static final Map<AdminActionType, SupportCategory> BY_ACTION =
            Map.of(
                    AdminActionType.BAN_USER, SupportCategory.APPEAL_BAN,
                    AdminActionType.SUSPEND_USER, SupportCategory.APPEAL_SUSPENSION,
                    AdminActionType.WARN_USER, SupportCategory.APPEAL_WARNING_STRIKE,
                    AdminActionType.REMOVE_POST, SupportCategory.APPEAL_CONTENT_REMOVAL,
                    AdminActionType.REMOVE_COMMENT, SupportCategory.APPEAL_CONTENT_REMOVAL,
                    AdminActionType.REMOVE_STORY, SupportCategory.APPEAL_CONTENT_REMOVAL,
                    AdminActionType.REMOVE_MESSAGE, SupportCategory.APPEAL_CONTENT_REMOVAL);

    private static final Set<String> APPEALABLE_LABELS =
            BY_ACTION.keySet().stream()
                    .map(type -> type.name().toLowerCase(Locale.ROOT))
                    .collect(Collectors.toUnmodifiableSet());

    private AppealCategories() {}

    /**
     * Resolves the appeal category for one action.
     *
     * @param actionType the recorded moderation action
     * @return the category, or null when the action carries no appeal link
     */
    public static SupportCategory forAction(AdminActionType actionType) {
        return BY_ACTION.get(actionType);
    }

    /**
     * The appealable action types, as the lowercase labels the {@code admin_action_type} enum uses.
     *
     * <p>Derived from the same map rather than restated, so the lost-link recovery path can never
     * offer a link for an action this class does not consider appealable. A second hand-written
     * list would be one edit away from disagreeing with the first.
     *
     * @return the appealable action type labels
     */
    public static Set<String> appealableActionTypeLabels() {
        return APPEALABLE_LABELS;
    }
}
