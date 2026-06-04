-- =====================================================================
-- Conddo Studio — seed Design Standard Library entries for Fashion +
-- Pharmacy (Slice F.3 follow-up / weekend-launch polish).
--
-- These rows are picked up automatically by AiAssistantService when it
-- builds the Claude prompt for a job in the matching vertical (Slice
-- F.3 wire-up). They give the AI an opinionated baseline — palette
-- hues, copy patterns, typography — so a hero block for a Lagos
-- pharmacy comes back with the right tone instead of generic AI fluff.
--
-- All entries are ON CONFLICT (id) DO NOTHING-equivalent via the
-- WHERE NOT EXISTS guard so a re-run is idempotent.
-- =====================================================================

-- ----- Pharmacy ------------------------------------------------------

INSERT INTO studio.design_standards
    (vertical, kind, name, description, content, is_active)
SELECT 'pharmacy', 'PALETTE',
       'Pharmacy — calming clinical greens',
       'Trustworthy, clinical-but-warm. Greens signal health + safety; clean white background; '
       || 'reserve red for warnings, never for branding (community pharmacies don''t want emergency-room vibes).',
       '{
            "primary": "#22C55E",
            "primaryHover": "#16A34A",
            "primaryLight": "#86EFAC",
            "primaryBg": "#F0FDF4",
            "background": "#FFFFFF",
            "surface": "#F9FAFB",
            "textPrimary": "#0F172A",
            "textSecondary": "#475569",
            "border": "#E2E8F0",
            "accent": "#0EA5E9"
        }'::jsonb,
       true
WHERE NOT EXISTS (
    SELECT 1 FROM studio.design_standards
    WHERE vertical = 'pharmacy' AND kind = 'PALETTE' AND name = 'Pharmacy — calming clinical greens'
);

INSERT INTO studio.design_standards
    (vertical, kind, name, description, content, is_active)
SELECT 'pharmacy', 'COPY_PATTERN',
       'Pharmacy — trust + community voice',
       'Lead with the patient outcome (in-stock, licensed, fast). Avoid medical disclaimers in marketing copy. '
       || 'Mention the local area when it''s a Lagos / Abuja / Port Harcourt pharmacy — Nigerian customers want to know it''s their pharmacy.',
       '{
            "headlinePatterns": [
                "Genuine medicines, always in stock",
                "Your trusted [neighbourhood] pharmacy",
                "Real prescriptions, dispensed by [pharmacist title]"
            ],
            "subheadlinePatterns": [
                "Licensed pharmacist on site • [hours] daily",
                "Authentic, NAFDAC-registered medicines"
            ],
            "ctaVerbs": ["Order now", "Call the pharmacist", "Find your medicine", "Book a refill"],
            "ctaToAvoid": ["Shop now", "Buy here", "Click here", "Get started"],
            "wordsToAvoid": ["miracle", "cure", "guaranteed", "best-in-class"],
            "tone": "Calm, expert, locally rooted. Short sentences. Lead with outcomes the patient can verify (in-stock today, NAFDAC-registered)."
        }'::jsonb,
       true
WHERE NOT EXISTS (
    SELECT 1 FROM studio.design_standards
    WHERE vertical = 'pharmacy' AND kind = 'COPY_PATTERN' AND name = 'Pharmacy — trust + community voice'
);

INSERT INTO studio.design_standards
    (vertical, kind, name, description, content, is_active)
SELECT 'pharmacy', 'LAYOUT',
       'Pharmacy — services + product-finder above the fold',
       'Hero with one clear CTA (call/order). Services strip with 3-4 service tiles (Prescription refills, OTC, Vaccinations, Delivery). Products / Find-my-medicine block before About.',
       '{
            "sectionOrder": ["hero", "services", "products", "about", "contact"],
            "heroNotes": "Single CTA. Photo of the pharmacist or the storefront — not a generic medicine cabinet.",
            "servicesNotes": "Tile per offering: Prescription refills, Over-the-counter, Vaccinations, Free delivery if applicable.",
            "trustSignals": "Show NAFDAC-registered badge, license number, pharmacist name visibly."
        }'::jsonb,
       true
WHERE NOT EXISTS (
    SELECT 1 FROM studio.design_standards
    WHERE vertical = 'pharmacy' AND kind = 'LAYOUT' AND name = 'Pharmacy — services + product-finder above the fold'
);

-- ----- Fashion -------------------------------------------------------

INSERT INTO studio.design_standards
    (vertical, kind, name, description, content, is_active)
SELECT 'fashion', 'PALETTE',
       'Fashion — Lagos boutique warm tones',
       'Warm, aspirational. Reds + ochres reflect Nigerian fabric heritage (Adire, Aso-Ebi). Keep the background near-white so the garment photos pop. Hover state shifts slightly warmer — not lighter.',
       '{
            "primary": "#B91C1C",
            "primaryHover": "#991B1B",
            "primaryLight": "#FECACA",
            "primaryBg": "#FEF2F2",
            "background": "#FFFBF6",
            "surface": "#FFFFFF",
            "textPrimary": "#1C1917",
            "textSecondary": "#57534E",
            "border": "#E7E5E4",
            "accent": "#F59E0B"
        }'::jsonb,
       true
WHERE NOT EXISTS (
    SELECT 1 FROM studio.design_standards
    WHERE vertical = 'fashion' AND kind = 'PALETTE' AND name = 'Fashion — Lagos boutique warm tones'
);

INSERT INTO studio.design_standards
    (vertical, kind, name, description, content, is_active)
SELECT 'fashion', 'COPY_PATTERN',
       'Fashion — personal craft voice',
       'Aspirational + personal. Reference the maker (the designer''s name, the team) — Nigerian fashion is bought from a person, not a brand. '
       || 'Lean into custom-made, made-for-you, attention to detail. Avoid generic e-commerce verbs.',
       '{
            "headlinePatterns": [
                "Made for you, by [designer name]",
                "Custom tailoring for the everyday woman",
                "Aso-Ebi sets that fit the way you wanted"
            ],
            "subheadlinePatterns": [
                "Bridal, Aso-Ebi, ready-to-wear — fitting in [city]",
                "Hand-cut, hand-sewn — measurements taken in person or by video"
            ],
            "ctaVerbs": ["Book a fitting", "Start a custom order", "See the lookbook", "Talk to the designer"],
            "ctaToAvoid": ["Add to cart", "Shop now", "Buy", "Order online"],
            "wordsToAvoid": ["fast fashion", "trendy", "fashion-forward", "iconic"],
            "tone": "Warm, personal, aspirational. Mention the craft (hand-cut, hand-finished). Reference the city when it''s Lagos / Abuja / Port Harcourt boutique."
        }'::jsonb,
       true
WHERE NOT EXISTS (
    SELECT 1 FROM studio.design_standards
    WHERE vertical = 'fashion' AND kind = 'COPY_PATTERN' AND name = 'Fashion — personal craft voice'
);

INSERT INTO studio.design_standards
    (vertical, kind, name, description, content, is_active)
SELECT 'fashion', 'LAYOUT',
       'Fashion — gallery-first storytelling',
       'Visual-heavy. Hero with one big garment photo (not a logo lockup). Gallery section EARLY (above About) because the work is the proof. Testimonials with real names + city. Services tile per garment type.',
       '{
            "sectionOrder": ["hero", "gallery", "services", "about", "testimonials", "contact"],
            "heroNotes": "Single garment photo — not a flat-lay. Three CTAs feels cluttered; one button + one secondary text link.",
            "galleryNotes": "Minimum 6 photos. Mix of process shots (cutting fabric, fitting) and finished garments — process humanises the brand.",
            "testimonialsNotes": "Real client first name + LGA (Lekki, Yaba, GRA) — Nigerian buyers want to see someone like them in the picture."
        }'::jsonb,
       true
WHERE NOT EXISTS (
    SELECT 1 FROM studio.design_standards
    WHERE vertical = 'fashion' AND kind = 'LAYOUT' AND name = 'Fashion — gallery-first storytelling'
);

-- ----- Cross-vertical ------------------------------------------------

INSERT INTO studio.design_standards
    (vertical, kind, name, description, content, is_active)
SELECT NULL, 'TYPOGRAPHY',
       'Conddo house typography',
       'Inter for body + headings (Latin extended covers Nigerian names well). Geist Mono for prices and SLA countdowns — same as the Studio FE.',
       '{
            "heading": "Inter",
            "body": "Inter",
            "mono": "Geist Mono",
            "scale": "modular 1.25",
            "weightHeading": 700,
            "weightBody": 400
        }'::jsonb,
       true
WHERE NOT EXISTS (
    SELECT 1 FROM studio.design_standards
    WHERE vertical IS NULL AND kind = 'TYPOGRAPHY' AND name = 'Conddo house typography'
);
