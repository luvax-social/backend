package com.app.modules.mail.service.impl;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.app.common.enums.ApiErrorCode;
import com.app.common.exception.AppException;
import com.app.modules.mail.config.MailProperties;
import com.app.modules.mail.config.resend.ResendProperties;
import com.app.modules.mail.util.MailTemplateRenderer;
import com.resend.Resend;
import com.resend.services.emails.model.CreateEmailOptions;
import com.resend.services.emails.model.CreateEmailResponse;

/**
 * Synchronous Resend-backed mail sender.
 *
 * <p>RabbitMQ consumers use this component so provider failures are observed before acknowledging a
 * message. Raw tokens must only appear inside the final recipient URL and must never be logged.
 */
@Component
@ConditionalOnProperty(prefix = "app.mail", name = "transport", havingValue = "resend")
public class ResendMailSender extends AbstractTemplateMailSender {

    private static final Logger log = LoggerFactory.getLogger(ResendMailSender.class);

    /**
     * The HTTP status where the SDK's message format puts it, e.g. {@code "Failed to send email:
     * 422 {...}"}.
     *
     * <p>Anchored to that position rather than matched anywhere. A word boundary is not enough: in
     * {@code req-451-ab} a hyphen is a non-word character on both sides, so {@code \b([45]\d{2})\b}
     * matches {@code 451} inside a request id and classifies a transient failure as permanent -
     * which dead-letters it on the first attempt with no retry, the exact unsafe direction {@link
     * #isPermanentRejection} says it avoids. The prefix is {@code
     * com.resend.services.emails.Emails}'s own literal, verified against the 3.1.0 jar.
     *
     * <p>If the SDK ever changes that prefix the parse stops matching and everything falls through
     * to transient, which is the safe direction: retrying a message that would have succeeded is
     * recoverable, dropping one that would have is not.
     */
    private static final Pattern PROVIDER_STATUS =
            Pattern.compile("^Failed to send email:\\s+([45]\\d{2})\\b");

    /**
     * An address-shaped run in provider text.
     *
     * <p>The provider's message is carried into {@code email_deliveries.error_text} and the
     * dead-letter reason header, and its body is whatever the provider chose to return - a
     * validation error naming the offending field can echo the address itself. That would put a
     * recipient address into two persistent sinks and a log line, which {@code
     * AbstractTemplateMailSender}'s contract forbids. The classification and the provider's own
     * wording are what an operator needs; the address is not, and the delivery row already holds
     * it.
     */
    private static final Pattern EMAIL_SHAPED = Pattern.compile("[\\w.+-]+@[\\w-]+(?:\\.[\\w-]+)+");

    private static final String REDACTED = "[redacted-address]";

    private final Resend resend;
    private final ResendProperties resendProperties;

    // One virtual thread per call. The pool exists only to make the SDK call cancellable from the
    // outside so the configured call timeout can be enforced; it is not a concurrency bound and
    // must not become one, because the consumer already bounds how many sends run at once.
    private final ExecutorService callExecutor =
            Executors.newThreadPerTaskExecutor(
                    Thread.ofVirtual().name("resend-call-", 0).factory());

    public ResendMailSender(
            Resend resend,
            ResendProperties resendProperties,
            MailProperties mailProperties,
            MailTemplateRenderer mailTemplateRenderer) {
        super(mailProperties, mailTemplateRenderer);
        this.resend = resend;
        this.resendProperties = resendProperties;
    }

    @PreDestroy
    void shutdown() {
        callExecutor.shutdownNow();
    }

    @Override
    protected String deliver(String toEmail, String subject, String htmlBody) {
        CreateEmailOptions options =
                CreateEmailOptions.builder()
                        .from(fromHeader())
                        .to(toEmail)
                        .subject(subject)
                        .html(htmlBody)
                        .build();
        Callable<CreateEmailResponse> call = () -> resend.emails().send(options);
        Future<CreateEmailResponse> pending = callExecutor.submit(call);
        try {
            CreateEmailResponse response =
                    pending.get(
                            resendProperties.getCallTimeout().toMillis(), TimeUnit.MILLISECONDS);
            String providerMessageId = response == null ? null : response.getId();
            log.info(
                    "Email sent | subject: {} | providerMessageId: {}", subject, providerMessageId);
            return providerMessageId;
        } catch (TimeoutException e) {
            // Cancels the waiting task, not the socket: the SDK owns the connection and exposes no
            // way to abort it. The call is abandoned and the thread is released when OkHttp's own
            // read timeout fires underneath.
            pending.cancel(true);
            log.error(
                    "Timed out sending email after {} | subject: {}",
                    resendProperties.getCallTimeout(),
                    subject);
            throw new AppException(ApiErrorCode.SERVICE_UNAVAILABLE);
        } catch (InterruptedException e) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new AppException(ApiErrorCode.SERVICE_UNAVAILABLE);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            // Classified on the raw text, then redacted before it reaches anywhere that keeps it.
            String rawMessage = cause.getMessage();
            boolean permanent = isPermanentRejection(rawMessage);
            String message = redactAddresses(rawMessage);
            log.error(
                    "Failed to send email | subject: {} | permanent: {} | error: {}",
                    subject,
                    permanent,
                    message);
            // A 422 about a malformed recipient and a 503 about the provider being down arrive
            // through this same branch. Collapsing both to SERVICE_UNAVAILABLE made the consumers'
            // retry classifier treat a permanent rejection as a transient outage: the message
            // burned the whole retry ladder, held a consumer thread behind it, and then landed in
            // the DLQ labelled "temporarily unavailable", which invites a replay that can only
            // fail identically.
            // The provider's own text is carried through deliberately. It becomes the DLQ's
            // x-dead-letter-reason header and the error_text column on email_deliveries, and an
            // operator reading either needs the real cause rather than a generic classification.
            // Both are internal operational records, not anything a caller is shown - but the text
            // is the provider's, not this class's, so any address-shaped run in it is redacted
            // first. Without that, a validation error naming the offending recipient writes an
            // address into two durable stores.
            throw new AppException(
                    permanent
                            ? ApiErrorCode.MAIL_PERMANENTLY_REJECTED
                            : ApiErrorCode.SERVICE_UNAVAILABLE,
                    (permanent
                                    ? "Mail provider permanently rejected the message: "
                                    : "Mail provider was unavailable: ")
                            + message);
        }
    }

    /**
     * Whether the provider refused the message itself rather than being unable to accept it now.
     *
     * <p>The Resend SDK does not expose the HTTP status as a field; it is only in the exception
     * message, which begins {@code "Failed to send email: 422 {...}"}. Parsing a message is fragile
     * and would not normally be acceptable, but the alternative is replacing the SDK with a client
     * that surfaces the status, which is far larger than this warrants. The parse is kept here, in
     * one small directly-tested method, and replacing the SDK is recorded as debt.
     *
     * <p>4xx is permanent except 408 and 429, which are the two the provider expects a client to
     * retry. Anything unparseable is treated as transient, because retrying a message that would
     * have succeeded is recoverable while dropping one that would have is not.
     *
     * <p>The status is read only from the position the SDK's format puts it in. A three-digit run
     * anywhere else in the text - a request id, a byte count - is not a status and must not be read
     * as one.
     *
     * @param message the provider exception's message, may be null
     * @return true when the message must not be retried
     */
    static boolean isPermanentRejection(String message) {
        if (message == null) {
            return false;
        }
        Matcher matcher = PROVIDER_STATUS.matcher(message);
        if (!matcher.find()) {
            return false;
        }
        int status = Integer.parseInt(matcher.group(1));
        if (status == 408 || status == 429) {
            return false;
        }
        return status >= 400 && status < 500;
    }

    /**
     * Masks any address-shaped run in provider text before it is stored or logged.
     *
     * @param message the provider exception's message, may be null
     * @return the same text with address-shaped runs replaced, or null when the input was null
     */
    static String redactAddresses(String message) {
        return message == null ? null : EMAIL_SHAPED.matcher(message).replaceAll(REDACTED);
    }
}
