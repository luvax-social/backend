package com.app.modules.support.service.impl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import com.app.common.enums.ApiErrorCode;
import com.app.common.exception.AppException;
import com.app.modules.support.enums.SupportCategory;
import com.app.modules.support.service.SupportTokenService;

@Service
public class SupportTokenServiceImpl implements SupportTokenService {

    private static final String APPEAL_PREFIX = "support:token:appeal:";
    private static final String CONFIRMATION_PREFIX = "support:token:confirmation:";
    private static final String INDEX_INFIX = "subject:";
    private static final String SHA_256 = "SHA-256";
    private static final String VALUE_SEPARATOR = "|";

    // Thirty days, against twenty-four hours for email verification and fifteen minutes for a
    // password reset. Those two bound a window the user opened seconds earlier and is waiting on.
    // An appeal window is the opposite: the notice arrives unannounced, is bad news, and is
    // routinely read late - after a holiday, or after the account holder works out what happened.
    // A window shorter than a month would expire the link for exactly the people least able to act
    // quickly, and the link authorises nothing but writing one ticket, so a long window costs
    // little. It is not indefinite because a token that never expires is a credential.
    private static final Duration APPEAL_TTL = Duration.ofDays(30);

    // Short, because the submitter is sitting at the form when it is sent and a stale confirmation
    // link should not keep an unconfirmed row alive.
    private static final Duration CONFIRMATION_TTL = Duration.ofHours(24);

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    // Atomic consume: GET then DEL. Returns the stored value or nil when absent. A token can
    // therefore be redeemed exactly once, however many requests race for it.
    private static final String CONSUME_SCRIPT =
            "local val = redis.call('GET', KEYS[1]) "
                    + "if val then "
                    + "  redis.call('DEL', KEYS[1]) "
                    + "  return val "
                    + "else "
                    + "  return nil "
                    + "end";

    // Atomic create: drop any prior token for the same subject, then set the forward and reverse
    // keys together. KEYS[1] is the reverse key, ARGV[1] the new hash, ARGV[2] the stored value,
    // ARGV[3] the prefix and ARGV[4] the TTL in seconds.
    private static final String CREATE_SCRIPT =
            "local prev = redis.call('GET', KEYS[1]) "
                    + "if prev then "
                    + "  redis.call('DEL', ARGV[3] .. prev) "
                    + "end "
                    + "redis.call('SET', ARGV[3] .. ARGV[1], ARGV[2], 'EX', tonumber(ARGV[4])) "
                    + "redis.call('SET', KEYS[1], ARGV[1], 'EX', tonumber(ARGV[4])) "
                    + "return ARGV[1]";

    // Atomic revoke: read the reverse key, then drop the forward and reverse keys together. The
    // mirror image of CREATE_SCRIPT, minus the new token. KEYS[1] is the reverse key and ARGV[1]
    // the prefix. Returns nothing the caller needs; revoking a subject that holds no token is the
    // ordinary case, not an error.
    private static final String REVOKE_SCRIPT =
            "local prev = redis.call('GET', KEYS[1]) "
                    + "if prev then "
                    + "  redis.call('DEL', ARGV[1] .. prev) "
                    + "  redis.call('DEL', KEYS[1]) "
                    + "end "
                    + "return 1";

    private final StringRedisTemplate redisTemplate;
    private final RedisScript<String> consumeScript;
    private final RedisScript<String> createScript;
    private final RedisScript<Long> revokeScript;

    public SupportTokenServiceImpl(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.consumeScript = new DefaultRedisScript<>(CONSUME_SCRIPT, String.class);
        this.createScript = new DefaultRedisScript<>(CREATE_SCRIPT, String.class);
        this.revokeScript = new DefaultRedisScript<>(REVOKE_SCRIPT, Long.class);
    }

    @Override
    public String createAppealToken(UUID userId, UUID adminActionId, SupportCategory category) {
        // Keyed on the audit row rather than the account, because one account may hold appealable
        // decisions against several actions at once and issuing the second must not invalidate the
        // first. Re-sending a notice for the same action does invalidate its previous link, which
        // is
        // the property the reverse key exists for.
        String value = userId + VALUE_SEPARATOR + adminActionId + VALUE_SEPARATOR + category.name();
        return create(APPEAL_PREFIX, adminActionId.toString(), value, APPEAL_TTL);
    }

    @Override
    public AppealGrant consumeAppealToken(String rawToken) {
        AppealGrant grant = parseAppealValue(consume(APPEAL_PREFIX, rawToken));
        redisTemplate.delete(APPEAL_PREFIX + INDEX_INFIX + grant.adminActionId());
        return grant;
    }

    @Override
    public AppealGrant peekAppealToken(String rawToken) {
        return parseAppealValue(peek(APPEAL_PREFIX, rawToken));
    }

    private static AppealGrant parseAppealValue(String value) {
        String[] parts = value.split("\\" + VALUE_SEPARATOR);
        if (parts.length != 3) {
            throw new AppException(ApiErrorCode.SUPPORT_TOKEN_INVALID);
        }
        try {
            return new AppealGrant(
                    UUID.fromString(parts[0]),
                    UUID.fromString(parts[1]),
                    SupportCategory.valueOf(parts[2]));
        } catch (IllegalArgumentException ex) {
            throw new AppException(ApiErrorCode.SUPPORT_TOKEN_INVALID);
        }
    }

    @Override
    public void revokeAppealToken(UUID adminActionId) {
        redisTemplate.execute(
                revokeScript, List.of(APPEAL_PREFIX + INDEX_INFIX + adminActionId), APPEAL_PREFIX);
    }

    @Override
    public String createConfirmationToken(UUID ticketId) {
        return create(
                CONFIRMATION_PREFIX, ticketId.toString(), ticketId.toString(), CONFIRMATION_TTL);
    }

    @Override
    public UUID consumeConfirmationToken(String rawToken) {
        UUID ticketId = parseTicketId(consume(CONFIRMATION_PREFIX, rawToken));
        redisTemplate.delete(CONFIRMATION_PREFIX + INDEX_INFIX + ticketId);
        return ticketId;
    }

    @Override
    public UUID peekConfirmationToken(String rawToken) {
        return parseTicketId(peek(CONFIRMATION_PREFIX, rawToken));
    }

    private static UUID parseTicketId(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            throw new AppException(ApiErrorCode.SUPPORT_TOKEN_INVALID);
        }
    }

    private String create(String prefix, String subjectId, String value, Duration ttl) {
        byte[] buf = new byte[32];
        SECURE_RANDOM.nextBytes(buf);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(buf);

        redisTemplate.execute(
                createScript,
                List.of(prefix + INDEX_INFIX + subjectId),
                sha256(rawToken),
                value,
                prefix,
                String.valueOf(ttl.toSeconds()));
        return rawToken;
    }

    private String consume(String prefix, String rawToken) {
        String value =
                redisTemplate.execute(consumeScript, List.of(prefix + hashOrRefuse(rawToken)));
        if (value == null) {
            throw new AppException(ApiErrorCode.SUPPORT_TOKEN_INVALID);
        }
        return value;
    }

    // Reads the token without spending it, so a caller can refuse a request while the token is
    // still redeemable. Deliberately a plain GET rather than the consume script: nothing here may
    // delete, because the whole point is that a refusal leaves the credential intact.
    private String peek(String prefix, String rawToken) {
        String value = redisTemplate.opsForValue().get(prefix + hashOrRefuse(rawToken));
        if (value == null) {
            throw new AppException(ApiErrorCode.SUPPORT_TOKEN_INVALID);
        }
        return value;
    }

    private static String hashOrRefuse(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new AppException(ApiErrorCode.SUPPORT_TOKEN_INVALID);
        }
        return sha256(rawToken);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance(SHA_256);
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the platform, so this cannot happen on a supported JVM.
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
