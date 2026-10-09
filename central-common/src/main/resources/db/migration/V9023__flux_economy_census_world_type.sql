-- Attributes a census row to the world type it counted.
--
-- V36 moved inventory_objs out of public into a schema per world type, so there is no longer a
-- single set of items to count: a character holds a different save in each mode. economy_census
-- stayed in public, which left the census statement joining a public table to one it could no
-- longer see - "relation inventory_objs does not exist".
--
-- The game server now runs the census once per served world type with search_path set to that
-- schema. Without this column the rows from two modes taken in the same pass would be
-- indistinguishable, and the "has one been taken recently" guard would see another mode's row and
-- skip. Existing rows predate the split, when every save was what is now main.
ALTER TABLE economy_census
    ADD COLUMN IF NOT EXISTS world_type TEXT NOT NULL DEFAULT 'main';

CREATE INDEX IF NOT EXISTS idx_economy_census_type_taken
    ON economy_census (world_type, taken_at DESC);
