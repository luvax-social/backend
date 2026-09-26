package com.app.modules.admin.messaging;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.app.common.messaging.exception.PermanentMessageException;
import com.app.common.outbox.model.DomainEventEnvelope;
import com.app.modules.admin.enums.AdminActionType;
import com.app.modules.auth.repository.UserCredentialRepository;
import com.app.modules.mail.config.MailProperties;
import com.app.modules.mail.entity.EmailDelivery;
import com.app.modules.mail.enums.EmailDeliveryStatus;
import com.app.modules.mail.enums.ModerationMailTemplate;
import com.app.modules.mail.repository.EmailDeliveryRepository;
import com.app.modules.mail.service.ModerationMailThrottle;
import com.app.modules.mail.service.impl.AbstractTemplateMailSender;
import com.app.modules.mail.util.MailSuppression;
import com.app.modules.support.enums.SupportCategory;
import com.app.modules.support.service.SupportTokenService;
import com.app.modules.users.entity.User;
import com.app.modules.users.repository.UserRepository;

/**
 * Renders and sends the notice for one moderation decision, and records the attempt.
 *
 * <p>This handler deliberately does NOT gate on {@code UserStatus.ACTIVE}, and that is the entire
 * reason it exists as a path separate from {@code AuthMailEventHandler}. That handler refuses any
 * account whose status is not ACTIVE, which is correct for a welcome or a password reset. Applied
 * here the same rule would suppress every notice that matters: a banned or suspended account is
 * precisely the audience for a ban or suspension notice, and {@code TokenPrincipalResolverImpl}
 * admits only ACTIVE accounts, so a disciplined user has no other way to learn what happened. The
 * absence of that gate is the feature. Do not add one.
 *
 * <p>Two refusals do remain, and both are narrower than a status check.
 *
 * <p>A soft-deleted account is refused: there is nobody left to tell.
 *
 * <p>An unverified email address is refused, which is a deliberate decision rather than an
 * oversight carried over from the auth path. The platform has never proved the address belongs to
 * the account. Sending a ban notice there would tell whoever actually owns that mailbox both that
 * the address is registered here and that a moderation decision was taken about its supposed owner,
 * which is a disclosure to a third party about a person who did not consent to it. Withholding the
 * notice is the lesser harm: the account still learns the outcome the next time it tries to sign
 * in, and the skipped send is recorded in {@code email_deliveries} so the gap is visible rather
 * than silent.
 */
@Component
public class ModerationMailEventHandler {

    private static final Logger log = LoggerFactory.getLogger(ModerationMailEventHandler.class);

    private static final DateTimeFormatter NOTICE_DATE_FORMAT =
            DateTimeFormatter.ofPattern("d MMMM uuuu", Locale.ENGLISH);

    private final UserRepository userRepository;
    private final UserCredentialRepository userCredentialRepository;
    private final AbstractTemplateMailSender mailSender;
    private final ModerationMailThrottle throttle;
    private final EmailDeliveryRepository emailDeliveryRepository;
    private final MailProperties mailProperties;
    private final SupportTokenService supportTokenService;

    public ModerationMailEventHandler(
            UserRepository userRepository,
            UserCredentialRepository userCredentialRepository,
            AbstractTemplateMailSender mailSender,
            ModerationMailThrottle throttle,
            EmailDeliveryRepository emailDeliveryRepository,
            MailProperties mailProperties,
            SupportTokenService supportTokenService) {
        this.userRepository = userRepository;
        this.userCredentialRepository = userCredentialRepository;
        this.mailSender = mailSender;
        this.throttle = throttle;
        this.emailDeliveryRepository = emailDeliveryRepository;
        this.mailProperties = mailProperties;
        this.supportTokenService = supportTokenService;
    }

    /**
     * Sends the notice one moderation event calls for.
     *
     * @param event the moderation notice request carrying the target, the action and the audit row
     */
    public void handle(DomainEventEnvelope event) {
        AdminActionType actionType = requireActionType(event);
        ModerationMailTemplate template = ModerationMailTemplates.forAction(actionType);
        if (template == null) {
            throw new PermanentMessageException(
                    "No moderation mail template for action " + actionType);
        }
        UUID userId = requireUuid(event, "userId");
        User user =
                userRepository
                        .findByIdAndDeletedAtIsNull(userId)
                        .orElseThrow(() -> new PermanentMessageException("User not found"));
        UUID adminActionId = optionalUuid(event, "adminActionId");

        if (!emailVerified(userId)) {
            recordTerminal(user, template, adminActionId, EmailDeliveryStatus.SKIPPED);
            log.info(
                    "Skipped moderation notice for an unverified address | action: {}", actionType);
            return;
        }
        if (!throttle.tryAcquire(user.getEmail())) {
            recordTerminal(user, template, adminActionId, EmailDeliveryStatus.THROTTLED);
            log.warn("Throttled moderation notice | action: {}", actionType);
            return;
        }

        EmailDelivery delivery =
                emailDeliveryRepository.save(
                        newDelivery(user, template, adminActionId, EmailDeliveryStatus.PENDING));
        try {
            String providerMessageId =
                    mailSender.sendModerationNotice(
                            template,
                            variables(
                                    event,
                                    user,
                                    template,
                                    appealUrl(actionType, userId, adminActionId)),
                            user.getEmail());
            delivery.setStatus(EmailDeliveryStatus.SENT);
            delivery.setProviderMessageId(providerMessageId);
            delivery.setAttemptCount(delivery.getAttemptCount() + 1);
            delivery.setSentAt(OffsetDateTime.now(ZoneOffset.UTC));
            emailDeliveryRepository.save(delivery);
        } catch (RuntimeException ex) {
            delivery.setAttemptCount(delivery.getAttemptCount() + 1);
            if (MailSuppression.isRecipientSuppressed(ex)) {
                // A deliberate configuration outcome, not a failure. Recorded so the suppression is
                // visible rather than silent, then swallowed: rethrowing would dead-letter a
                // message this deployment was configured never to send, and fill the DLQ with
                // decisions the operator already made.
                delivery.setStatus(EmailDeliveryStatus.SKIPPED);
                delivery.setErrorText(ex.getMessage());
                emailDeliveryRepository.save(delivery);
                return;
            }
            // Recorded before rethrowing so the failure survives the consumer's retry and
            // dead-letter handling rather than existing only in a log line.
            delivery.setStatus(EmailDeliveryStatus.FAILED);
            delivery.setErrorText(ex.getMessage());
            emailDeliveryRepository.save(delivery);
            throw ex;
        }
    }

    private boolean emailVerified(UUID userId) {
        return userCredentialRepository
                .findByUserId(userId)
                .map(credential -> credential.isEmailVerified())
                // An account with no credential row signed in through OAuth2 only. The provider
                // verified the address before it ever reached this system, so the notice is sent.
                .orElse(true);
    }

    /**
     * Mints the single-use appeal link, or returns null when the action is not appealable.
     *
     * <p>Minted here rather than when the action is recorded, so a notice that is never sent - a
     * throttled or skipped one - never leaves a live token behind.
     */
    private String appealUrl(
            com.app.modules.admin.enums.AdminActionType actionType,
            UUID userId,
            UUID adminActionId) {
        SupportCategory category = AppealCategories.forAction(actionType);
        if (category == null || adminActionId == null) {
            return null;
        }
        String token = supportTokenService.createAppealToken(userId, adminActionId, category);
        return org.springframework.web.util.UriComponentsBuilder.fromUriString(
                        mailProperties.getFrontendBaseUrl())
                .path("/support/appeal")
                .queryParam("token", token)
                .build()
                .toUriString();
    }

    private Map<String, Object> variables(
            DomainEventEnvelope event,
            User user,
            ModerationMailTemplate template,
            String appealUrl) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("toName", displayName(user));
        variables.put("appName", mailProperties.getAppName());
        variables.put("occurredAt", NOTICE_DATE_FORMAT.format(event.occurredAt()));
        variables.put("showStandardsLine", template.isShowsStandardsLine());
        variables.put("suspendedUntil", formattedSuspendedUntil(event));
        // Present only for the two support ticket notices. The ticket's internal note is never in
        // the payload at all, so no template can render it even by mistake.
        Object supportResponse = event.data() == null ? null : event.data().get("supportResponse");
        variables.put(
                "supportResponse", supportResponse == null ? null : supportResponse.toString());
        // Null for the two reinstating actions and the two ticket replies, which the layout reads
        // as
        // "render no appeal block".
        variables.put("appealUrl", appealUrl);
        return variables;
    }

    private static String formattedSuspendedUntil(DomainEventEnvelope event) {
        Object raw = event.data() == null ? null : event.data().get("suspendedUntil");
        if (raw == null) {
            return null;
        }
        try {
            return NOTICE_DATE_FORMAT.format(OffsetDateTime.parse(raw.toString()));
        } catch (RuntimeException ex) {
            throw new PermanentMessageException("Event carries a malformed suspendedUntil");
        }
    }

    private void recordTerminal(
            User user,
            ModerationMailTemplate template,
            UUID adminActionId,
            EmailDeliveryStatus status) {
        emailDeliveryRepository.save(newDelivery(user, template, adminActionId, status));
    }

    private static EmailDelivery newDelivery(
            User user,
            ModerationMailTemplate template,
            UUID adminActionId,
            EmailDeliveryStatus status) {
        return EmailDelivery.builder()
                .recipientUserId(user.getId())
                .recipientEmail(user.getEmail())
                .templateKey(template.name())
                .adminActionId(adminActionId)
                .status(status)
                .attemptCount(0)
                .build();
    }

    private static String displayName(User user) {
        if (user.getDisplayName() != null && !user.getDisplayName().isBlank()) {
            return user.getDisplayName();
        }
        return user.getUsername();
    }

    private static UUID requireUuid(DomainEventEnvelope event, String key) {
        Object raw = event.data() == null ? null : event.data().get(key);
        if (raw == null) {
            throw new PermanentMessageException("Event is missing " + key);
        }
        try {
            return UUID.fromString(raw.toString());
        } catch (IllegalArgumentException ex) {
            throw new PermanentMessageException("Event carries a malformed " + key);
        }
    }

    private static UUID optionalUuid(DomainEventEnvelope event, String key) {
        Object raw = event.data() == null ? null : event.data().get(key);
        if (raw == null) {
            return null;
        }
        try {
            return UUID.fromString(raw.toString());
        } catch (IllegalArgumentException ex) {
            throw new PermanentMessageException("Event carries a malformed " + key);
        }
    }

    private static AdminActionType requireActionType(DomainEventEnvelope event) {
        Object raw = event.data() == null ? null : event.data().get("actionType");
        if (raw == null) {
            throw new PermanentMessageException("Event is missing actionType");
        }
        try {
            return AdminActionType.valueOf(raw.toString());
        } catch (IllegalArgumentException ex) {
            throw new PermanentMessageException("Event carries an unknown actionType");
        }
    }
}
