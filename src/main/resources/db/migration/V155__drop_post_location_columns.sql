-- Drops the free-form location columns now that every label has been migrated to places and every
-- original value, coordinates included, sits in archived_post_locations (V154).

ALTER TABLE posts DROP COLUMN location_name;
ALTER TABLE posts DROP COLUMN latitude;
ALTER TABLE posts DROP COLUMN longitude;
