-- W3C trace context of the request that wrote the event, so the publisher can make the broker
-- send a child of that request and every consumer part of the same trace. Nullable: events
-- written outside any trace (seed replay, a job with tracing filtered out) carry none.
ALTER TABLE outbox_events
    ADD COLUMN trace_parent VARCHAR(55),
    ADD COLUMN trace_state  VARCHAR(512);

ALTER TABLE outbox_events
    ADD CONSTRAINT chk_outbox_events_trace_parent_format
        CHECK (trace_parent IS NULL
               OR trace_parent ~ '^[0-9a-f]{2}-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$');

ALTER TABLE outbox_events
    ADD CONSTRAINT chk_outbox_events_trace_state_needs_parent
        CHECK (trace_state IS NULL OR trace_parent IS NOT NULL);
