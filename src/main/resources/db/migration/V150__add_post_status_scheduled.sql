-- Adds the lifecycle state a post waits in until its scheduled publication time.
--
-- This file contains nothing but the ALTER TYPE statement. PostgreSQL does not make a new enum
-- value usable until the adding transaction has committed, so the column and the constraint that
-- name 'scheduled' live in V151.

ALTER TYPE post_status ADD VALUE IF NOT EXISTS 'scheduled' AFTER 'draft';
