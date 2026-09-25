<p align="center">
    <a href="https://luvax.online/" target="_blank"><img src="docs/asset/luvax-logo-warm.svg" height="90" alt="Luvax" /></a>&nbsp;
</p>

## Observability

The application serves two ports.
Port 8080 is the API port, unchanged.
Port 8081 is a private management port serving only `/actuator/health` and `/actuator/prometheus`; it is reachable only inside the deployment network and is never published to the host or the internet.

Trace and log export over OTLP is off by default.
Set `OTLP_EXPORT_ENABLED=true` and point `OTLP_ENDPOINT` at a collector to enable it; both variables are documented in `.env.example`.
With export on, every JDBC and Redis call, the Gorse and Turnstile HTTP clients, and every RabbitMQ publish and listener invocation produce a trace span, and application logs carry the real trace and span id instead of `none`.
One trace spans the outbox: the request that wrote an event is restored as the parent of its broker send, so the send and every consumer descend from the originating request.

To run with the full observability stack (OpenTelemetry Collector, ClickHouse, Prometheus, Grafana) locally, see `observability/README.md` in the root aggregator repository.
Without it, traces and logs go nowhere when export is enabled; the application does not fail, it just has no collector to send to.

With export disabled, or with no collector reachable, traces and logs live only in the local rolling file at `LOG_PATH` (3 days, 200 MB cap) and the console.
With export enabled and a collector running, they live in ClickHouse (14 day retention) and are queried through Grafana; Prometheus scrapes `/actuator/prometheus` on the management port for metrics.