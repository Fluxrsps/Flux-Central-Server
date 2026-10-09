-- The character's save for this world type, created the first time they play it.
INSERT INTO character_progress (character_id)
VALUES (?)
ON CONFLICT (character_id) DO NOTHING
