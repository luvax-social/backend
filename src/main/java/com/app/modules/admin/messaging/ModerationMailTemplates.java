package com.app.modules.admin.messaging;

import java.util.Map;

import com.app.modules.admin.enums.AdminActionType;
import com.app.modules.mail.enums.ModerationMailTemplate;

/**
 * The closed mapping from a moderation action to the notice its subject receives.
 *
 * <p>Fourteen actions produce mail: the nine moderation decisions, the two that answer a support
 * ticket, and the three verification decisions. The ticket notices travel this path rather than a
 * new one precisely because it applies no ACTIVE-only gate - a banned account asking to be unbanned
 * is the case that has to work. Every other action type is absent on purpose, and the absences
 * carry as much intent as the entries:
 *
 * <ul>
 *   <li>No {@code RESTORE_*} action mails. Telling someone their content is back draws attention to
 *       a removal they may never have noticed, and the restore is not a decision they need to act
 *       on.
 *   <li>{@code ISSUE_STRIKE} does not mail. A strike always accompanies a status change that mails
 *       already, and the warning that produced it mailed when it was issued, so a third message
 *       would say nothing new.
 *   <li>{@code FORCE_LOGOUT}, {@code REVOKE_SESSION}, {@code CHANGE_USER_ROLE}, {@code
 *       REVOKE_WARNING}, {@code REVOKE_STRIKE}, {@code ESCALATE_REPORT} and every hashtag action do
 *       not mail. They are either invisible to the account or internal to the moderation queue.
 * </ul>
 */
public final class ModerationMailTemplates {

    private static final Map<AdminActionType, ModerationMailTemplate> BY_ACTION =
            // Map.ofEntries rather than Map.of: there are eleven pairs and Map.of stops at ten.
            Map.ofEntries(
                    Map.entry(AdminActionType.BAN_USER, ModerationMailTemplate.ACCOUNT_BANNED),
                    Map.entry(
                            AdminActionType.UNBAN_USER, ModerationMailTemplate.ACCOUNT_REINSTATED),
                    Map.entry(
                            AdminActionType.SUSPEND_USER, ModerationMailTemplate.ACCOUNT_SUSPENDED),
                    Map.entry(
                            AdminActionType.UNSUSPEND_USER,
                            ModerationMailTemplate.ACCOUNT_SUSPENSION_ENDED),
                    Map.entry(AdminActionType.WARN_USER, ModerationMailTemplate.ACCOUNT_WARNING),
                    Map.entry(AdminActionType.REMOVE_POST, ModerationMailTemplate.POST_REMOVED),
                    Map.entry(
                            AdminActionType.REMOVE_COMMENT, ModerationMailTemplate.COMMENT_REMOVED),
                    Map.entry(AdminActionType.REMOVE_STORY, ModerationMailTemplate.STORY_REMOVED),
                    Map.entry(
                            AdminActionType.REMOVE_MESSAGE, ModerationMailTemplate.MESSAGE_REMOVED),
                    Map.entry(
                            AdminActionType.RESPOND_SUPPORT_TICKET,
                            ModerationMailTemplate.SUPPORT_TICKET_ANSWERED),
                    Map.entry(
                            AdminActionType.REJECT_SUPPORT_TICKET,
                            ModerationMailTemplate.SUPPORT_TICKET_REJECTED),
                    Map.entry(
                            AdminActionType.GRANT_VERIFICATION,
                            ModerationMailTemplate.VERIFICATION_GRANTED),
                    Map.entry(
                            AdminActionType.REJECT_VERIFICATION,
                            ModerationMailTemplate.VERIFICATION_REJECTED),
                    Map.entry(
                            AdminActionType.REVOKE_VERIFICATION,
                            ModerationMailTemplate.VERIFICATION_REVOKED));

    private ModerationMailTemplates() {}

    /**
     * Resolves the notice template for one action.
     *
     * @param actionType the recorded moderation action
     * @return the template to render, or null when the action deliberately sends no mail
     */
    public static ModerationMailTemplate forAction(AdminActionType actionType) {
        return BY_ACTION.get(actionType);
    }

    /**
     * Reports whether an action produces a notice, so a caller can decide whether to enqueue one.
     *
     * @param actionType the recorded moderation action
     * @return true when this action mails its subject
     */
    public static boolean mails(AdminActionType actionType) {
        return BY_ACTION.containsKey(actionType);
    }
}
