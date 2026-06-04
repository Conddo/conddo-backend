-- =====================================================================
-- Conddo Studio — DSL entries for the new music_studio vertical.
-- Mirrors V6's shape: PALETTE + COPY_PATTERN + LAYOUT entries that
-- AiAssistantService picks up automatically when the job's vertical
-- is music_studio (or music-studio).
--
-- Idempotent — WHERE NOT EXISTS so re-runs are safe.
-- =====================================================================

-- ----- Music Studio --------------------------------------------------

INSERT INTO studio.design_standards
    (vertical, kind, name, description, content, is_active)
SELECT 'music_studio', 'PALETTE',
       'Music Studio — late-night console glow',
       'Deep amber + magenta hint, near-black surface. The vibe is "studio at 2am, console lit, '
       || 'master fader pushed." Avoid daylight pastels — artists want to feel the room is serious '
       || 'about sound. Type stays high-contrast white so credits + room rates read at a glance.',
       '{
            "primary": "#F59E0B",
            "primaryHover": "#D97706",
            "primaryLight": "#FCD34D",
            "primaryBg": "#1F1611",
            "background": "#0B0B0F",
            "surface": "#16161D",
            "textPrimary": "#FAFAFA",
            "textSecondary": "#A1A1AA",
            "border": "#27272A",
            "accent": "#D946EF"
        }'::jsonb,
       true
WHERE NOT EXISTS (
    SELECT 1 FROM studio.design_standards
    WHERE vertical = 'music_studio' AND kind = 'PALETTE' AND name = 'Music Studio — late-night console glow'
);

INSERT INTO studio.design_standards
    (vertical, kind, name, description, content, is_active)
SELECT 'music_studio', 'COPY_PATTERN',
       'Music Studio — gear-specific creator voice',
       'Lead with the gear + the engineer when there''s real signal chain to name (SSL, Neumann, '
       || 'Pro Tools, Universal Audio). Name-drop signature releases mixed/mastered in the room '
       || 'when the artist gave permission. Avoid "state of the art" / "world-class" — those signal '
       || 'AI-generated to any artist who has ever booked a real studio. The CTA isn''t "Contact us"; '
       || 'it''s "Book a session" or "Tour the room."',
       '{
            "headlinePatterns": [
                "[City]''s [Adjective] room for [Genre] artists",
                "Recorded, mixed, mastered — in one room",
                "Engineered by [Name]. Booked by appointment.",
                "Where [Project Name] was tracked"
            ],
            "subheadlinePatterns": [
                "Pro Tools | SSL console | Neumann mics",
                "Acoustically treated live room + isolated vocal booth",
                "Hourly + project rates available — deposit secures the slot"
            ],
            "ctaVerbs": ["Book a session", "Reserve the room", "Take a tour", "Talk to the engineer"],
            "ctaToAvoid": ["Contact us", "Get started", "Learn more", "Click here", "Shop now"],
            "wordsToAvoid": [
                "state-of-the-art", "world-class", "premier", "leading", "best-in-class",
                "cutting-edge", "next-generation"
            ],
            "tone": "Confident, technical, lived-in. Mention specific gear by brand + model when the brief lists it. Reference past projects by name + artist when the brief includes them. Lagos / Abuja / Port Harcourt locality is fine to mention. Short sentences. The artist scrolling past should know in one glance whether this is the room for their session."
        }'::jsonb,
       true
WHERE NOT EXISTS (
    SELECT 1 FROM studio.design_standards
    WHERE vertical = 'music_studio' AND kind = 'COPY_PATTERN' AND name = 'Music Studio — gear-specific creator voice'
);

INSERT INTO studio.design_standards
    (vertical, kind, name, description, content, is_active)
SELECT 'music_studio', 'LAYOUT',
       'Music Studio — rooms + booking widget first',
       'Hero, then ROOMS (not "About") — artists need to see the room photo + hourly rate within '
       || 'the first scroll. Equipment list before Credits. Booking widget with live calendar + '
       || 'deposit field is the engine of the page; place it before About and Contact.',
       '{
            "sectionOrder": ["hero", "rooms", "services", "equipment", "credits", "booking", "contact"],
            "heroNotes": "Photo of the room with the lights on at night — console, monitors, mic in frame. Single CTA reading ''Book a session''. No carousel; one hero shot, one button.",
            "roomsNotes": "Per-room tile: photo + room name + hourly rate + 1-line acoustic description + capacity. Tap-through to a room detail page when more than 2 rooms.",
            "equipmentNotes": "Group by signal chain: Console / Mics / Monitors / Outboard / DAW. List by brand + model. Skip generic ''and more'' — list everything or omit the section.",
            "creditsNotes": "Real project names. Real artist names. Mixed-and-mastered-here is the signal artists are scanning for. Include release year + Apple Music / Spotify link when available.",
            "bookingNotes": "Live calendar, hourly slots, deposit field. Deposit-at-booking is the killer feature — never hide it behind ''Contact us''."
        }'::jsonb,
       true
WHERE NOT EXISTS (
    SELECT 1 FROM studio.design_standards
    WHERE vertical = 'music_studio' AND kind = 'LAYOUT' AND name = 'Music Studio — rooms + booking widget first'
);
