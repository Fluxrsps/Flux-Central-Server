/* World types are the gameplay modes a world can serve: `main` plus seasonal events such as the
   leagues and gridmaster. The set is a fixed enum in the game server (`org.rsmod.game.world.WorldType`)
   and each world picks which ones it serves in `game.yml`, so Central stores only the key and never
   needs a table of modes of its own. */

-- The mode the account's next login uses. NULL means `main`.
ALTER TABLE accounts
    ADD COLUMN IF NOT EXISTS active_world_type TEXT NULL;

-- Which mode a log row came from. `world_id` cannot stand in: one world may serve several.
ALTER TABLE activity_logs
    ADD COLUMN IF NOT EXISTS world_type TEXT NULL;

CREATE INDEX IF NOT EXISTS idx_activity_logs_world_type_time
    ON activity_logs (world_type, occurred_at DESC)
    WHERE world_type IS NOT NULL;
