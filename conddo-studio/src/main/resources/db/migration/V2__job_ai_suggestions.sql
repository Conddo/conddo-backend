-- =====================================================================
-- V2 — AI assistant (§8). Store the Claude-generated copy suggestions on the job
-- so they appear in the job detail (Phase 5 "definition of done"). Keyed by
-- section (HERO / SERVICES / ABOUT / …); empty until the developer requests them.
-- =====================================================================

ALTER TABLE studio.jobs ADD COLUMN ai_suggestions JSONB NOT NULL DEFAULT '{}'::jsonb;
