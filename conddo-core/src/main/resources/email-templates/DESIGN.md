# Email template design system

Rules every template in this folder MUST follow. Consistency across
emails is what makes them read as "from Conddo" rather than as a
random inbox spammer.

## Structure (top → bottom)

```
outer body        (#F4F3F0, 40px vertical padding on <td>)
  600px table     (single column, max-width 600px)
    logo row      (28px height, left-aligned, 24px bottom padding)
    card          (#FFFFFF, 14px radius, 1px #ECEAE6 border, 40px pad)
      heading     (Inter 20-22px, #141414, weight 600)
      body        (Inter 15px, #565656, line-height 24px)
      code/token  (Geist Mono, 38px, #7C5CBF on #F1ECFA)   [optional]
      CTA         (MSO roundrect + <a>, 14×28 padding, 10px radius)
      panel       (#FAF9F7, 1px #ECEAE6, 12px radius)      [optional]
      small print (Inter 13px, #8A8A8A, line-height 20px)
    footer        (Inter 12px, #9A9A9A, 18px line-height)
```

## Colours

| Token | Hex | Where |
|---|---|---|
| Outer background | `#F4F3F0` | body, code-inline |
| Card background | `#FFFFFF` | main content |
| Card border | `#ECEAE6` | card + panels |
| Panel background | `#FAF9F7` | supplementary info |
| Primary | `#7C5CBF` | CTA fill, code text, list numbers |
| Primary tint | `#F1ECFA` | code block background |
| Primary tint border | `#E2D8F5` | code block border |
| Heading text | `#141414` | h-level content |
| Body text | `#565656` | paragraph |
| Small print | `#8A8A8A` | expiry / disclaimer |
| Footer text | `#9A9A9A` | brand line |

## Typography

- Primary stack: `'Inter',-apple-system,'Segoe UI',Roboto,Helvetica,Arial,sans-serif`
- Monospace stack: `'Geist Mono','SF Mono',Consolas,Menlo,monospace`
- MSO fallback declared in `<head>`: `* { font-family: Arial, sans-serif !important; }`
- Never inline `font-family` on Outlook-only elements — MSO ignores custom fonts anyway.

## The button

Always use this exact bulletproof pattern so Outlook renders a filled
rectangle rather than a broken CSS button:

```html
<!--[if mso]>
<v:roundrect ... href="{{URL}}" style="height:48px;v-text-anchor:middle;width:200px;" arcsize="22%" stroke="f" fillcolor="#7C5CBF">
  <w:anchorlock/>
  <center style="color:#ffffff;font-family:Arial,sans-serif;font-size:15px;font-weight:bold;">Label</center>
</v:roundrect>
<![endif]-->
<!--[if !mso]><!-- -->
<a href="{{URL}}" style="display:inline-block; background-color:#7C5CBF; color:#FFFFFF; ... padding:14px 28px; border-radius:10px;">Label</a>
<!--<![endif]-->
```

The `width:` on the VML rect must accommodate the longest label.

## Preheader

Every template ends with an invisible preview-text `<div>` right after
`<body>`. First ~90 chars show in the inbox row alongside the subject:

```html
<div style="display:none; max-height:0; overflow:hidden; mso-hide:all; font-size:1px; line-height:1px; color:#F4F3F0;">
  Your one-line summary here.&zwnj;&nbsp;&zwnj;&nbsp;&zwnj;&nbsp;&zwnj;&nbsp;
</div>
```

The `&zwnj;&nbsp;` sequence pushes truncated fallback text out of view.

## Placeholders

`{{PLACEHOLDER}}` — resolved by `EmailTemplates.render()` via simple
string substitution. `{{LOGO_URL}}` is filled automatically for every
template from `conddo.notifications.email.logo-url` (or a derived URL).

Never inline literal URLs, prices, or tenant names — always route
them through placeholders so a copy tweak doesn't require a code
change.

## Do / don't

- ✅ Table-based layout only. Flexbox/grid don't survive Gmail.
- ✅ Every visible `<img>` gets an `alt` attribute.
- ✅ Use `role="presentation"` on layout tables.
- ✅ Test in the Chromatic email preview OR a Litmus test before shipping.
- ❌ Don't use web fonts (`<link href="fonts.googleapis...">`) — 60% of
     clients block external CSS.
- ❌ Don't use JS. Ever.
- ❌ Don't rely on background images — they're stripped in Outlook.
- ❌ Don't use `<style>` blocks in `<head>` beyond the MSO block —
     use inline styles so Gmail's CSS stripping doesn't nuke the design.

## When adding a new template

1. Copy the closest existing template (probably `staff-invite.html`).
2. Change the copy, keep the structure.
3. Register any new placeholders in the corresponding
   `NotificationService.sendX()` method.
4. Test render by wiring a temporary controller endpoint that returns
   the rendered HTML, then load it in Litmus / Email on Acid.
