package com.app.modules.social.policy.impl;

import java.time.OffsetDateTime;

/**
 * State shared by every rule during one evaluation.
 *
 * @param now the single instant every time comparison in the evaluation uses, so a limit expiry and
 *     the recent-follower window can never disagree about the current time
 */
record EvaluationContext(OffsetDateTime now) {}
