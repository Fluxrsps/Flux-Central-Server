-- The obj an identity held before it changed id. Zero for splits and merges, where the id is the
-- same on both sides and only the identity moved.
ALTER TABLE economy_lineage
    ADD COLUMN IF NOT EXISTS from_obj_id INTEGER NOT NULL DEFAULT 0;
