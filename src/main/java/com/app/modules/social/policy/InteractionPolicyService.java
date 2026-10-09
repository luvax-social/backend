package com.app.modules.social.policy;

/**
 * Decides whether one account may comment on, mention, start a conversation with, or send a message
 * to another, and where the result goes.
 *
 * <p>Every comment, mention, conversation initiation and message send is evaluated by one rule
 * chain, in this fixed order:
 *
 * <ol>
 *   <li>Block: a block in either direction between the actor and the target, or any secondary
 *       target, denies with {@link InteractionReason#BLOCKED}. It runs first and stops the chain,
 *       so a blocked pair never costs a limit, settings or hidden-word lookup.
 *   <li>The target's interaction limit: while the target's limit is in force, an actor who does not
 *       follow the target (when non-followers are limited), or whose follow was accepted within the
 *       last 7 days (when recent followers are limited), is hidden.
 *   <li>Message permission, for a conversation initiation: {@code nobody} denies, {@code following}
 *       denies unless the recipient follows the actor, and {@code everyone} routes an actor the
 *       recipient does not follow into the recipient's requests. A requester's send into a pending
 *       conversation goes to the requests as well.
 *   <li>The pending-request rule, for a send: while a request is pending or was declined, the
 *       requester may send exactly one text message and nothing else, and every refusal answers
 *       {@link InteractionReason#AWAITING_ACCEPTANCE} so a declined request reads as a pending one.
 *       The recipient's own send is allowed, and the message module accepts the request on it.
 *   <li>Hidden Words: a comment, or a requester's first message, whose text matches the target's
 *       personal list (or the platform-wide list, unless the target turned it off) is hidden.
 * </ol>
 *
 * <p>The five outcomes are {@link InteractionOutcome#ALLOW}, {@link
 * InteractionOutcome#ALLOW_HIDDEN}, {@link InteractionOutcome#ROUTE_TO_REQUESTS}, {@link
 * InteractionOutcome#ROUTE_TO_HIDDEN_REQUESTS} and {@link InteractionOutcome#DENY}.
 *
 * <p>Hiding is a conversion, not an outcome of its own. A hide turns {@code ALLOW} into {@code
 * ALLOW_HIDDEN} for a comment or mention, and turns {@code ROUTE_TO_REQUESTS} into {@code
 * ROUTE_TO_HIDDEN_REQUESTS} for a message; it never touches a message that is allowed, so a limit
 * or a hidden word never reaches into an accepted conversation, or into one the recipient's follow
 * lets straight through. The reported reason is the first deny, else the first hide that took
 * effect, else the reason the base outcome was chosen, else {@link InteractionReason#NONE}.
 *
 * <p>An actor acting on themselves is allowed with {@link InteractionReason#SELF}, after only a
 * block check against the secondary targets.
 */
public interface InteractionPolicyService {

    /**
     * Evaluates one interaction against the rule chain.
     *
     * <p>Read-only: it writes nothing and publishes nothing. Every time comparison in one call uses
     * the same instant.
     *
     * @param request the interaction to evaluate
     * @return the outcome and the reason for it
     * @throws IllegalArgumentException when the type is one no rule handles yet ({@code TAG},
     *     {@code STORY_REPLY}, {@code STORY_RESPONSE}), or a message send carries no conversation
     */
    InteractionDecision evaluate(InteractionRequest request);
}
