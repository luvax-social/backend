-- Query statistics for the monitoring stack. The extension object can be created without the
-- library preloaded; reading the view needs shared_preload_libraries = 'pg_stat_statements',
-- set in docker/postgres locally and in the Coolify custom configuration in production.
-- Creating it needs a superuser; production creates it before deploy, which makes this a no-op.
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;
