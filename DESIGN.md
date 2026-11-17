---
name: KiadásKövető
description: Offline Hungarian receipt and spending tracker — thermal-paper calm, forint-green accent, every number traceable to a receipt line.
colors:
  primary: "#0E6B50"
  on-primary: "#FFFFFF"
  primary-container: "#CDEFDF"
  on-primary-container: "#00382A"
  secondary: "#4E6359"
  secondary-container: "#D9E8E0"
  review-amber: "#855400"
  review-container: "#FFDDB4"
  error: "#BA1A1A"
  error-container: "#FFDAD6"
  ground: "#F6F7F5"
  surface-lowest: "#FFFFFF"
  surface-container: "#EAEEEB"
  surface-container-high: "#E4E9E5"
  ink: "#171D1A"
  ink-muted: "#404944"
  outline: "#707973"
  outline-variant: "#C0C9C3"
  chart-accent: "#0E7A5A"
  chart-muted: "#C9D1CC"
  chart-grid: "#E3E8E4"
  positive: "#006D3F"
  dark-ground: "#0F1412"
  dark-surface-container-low: "#171D1A"
  dark-primary: "#7FD8B4"
  dark-chart-accent: "#33A77C"
  dark-review-amber: "#F6BC6A"
  dark-paper: "#1E2522"
typography:
  hero:
    fontFamily: "Roboto (system), sans-serif"
    fontSize: "52sp"
    fontWeight: 600
    lineHeight: "58sp"
    letterSpacing: "-1.2sp"
  headline:
    fontFamily: "Roboto (system), sans-serif"
    fontSize: "28sp"
    fontWeight: 600
    letterSpacing: "-0.3sp"
  title:
    fontFamily: "Roboto (system), sans-serif"
    fontSize: "16sp"
    fontWeight: 600
  body:
    fontFamily: "Roboto (system), sans-serif"
    fontSize: "16sp"
    fontWeight: 400
  label:
    fontFamily: "Roboto (system), sans-serif"
    fontSize: "12sp"
    fontWeight: 500
rounded:
  xs: "6dp"
  sm: "10dp"
  md: "14dp"
  lg: "20dp"
  xl: "28dp"
spacing:
  xs: "4dp"
  sm: "8dp"
  md: "12dp"
  lg: "16dp"
  xl: "20dp"
  xxl: "28dp"
  section: "32dp"
  gutter: "20dp"
components:
  card:
    backgroundColor: "{colors.surface-lowest}"
    rounded: "{rounded.lg}"
    padding: "16dp"
  fab-add-receipt:
    backgroundColor: "{colors.primary-container}"
    textColor: "{colors.on-primary-container}"
    rounded: "{rounded.lg}"
  button-primary:
    backgroundColor: "{colors.primary}"
    textColor: "{colors.on-primary}"
    rounded: "{rounded.xl}"
    height: "52dp"
  review-marker:
    backgroundColor: "{colors.review-container}"
    textColor: "#2A1800"
    rounded: "{rounded.xl}"
    padding: "3dp 8dp"
  receipt-slip:
    backgroundColor: "{colors.surface-lowest}"
    textColor: "{colors.ink}"
    padding: "20dp"
---

# Design System: KiadásKövető

## Overview

A calm, precise Material 3 finance app built around one object: the supermarket receipt. Light mode reads as
thermal-paper white cards on a faintly green-grey ground; dark mode as deep green-black with lifted paper. One accent,
forint green, marks the current period, primary actions and positive state. Amber is reserved for "check this"; red for
over-budget and destructive actions. Expression lives in precision — exact figures, tabular columns, honest review
markers — and in one signature object: the receipt slip with a perforated tear edge.

## Colors

### Primary
Forint green `#0E6B50` (dark `#7FD8B4`): primary buttons, selected navigation, current-period emphasis, links.
Charts use the validated chart accent `#0E7A5A` / `#33A77C` (≥ 3:1 on their chart surfaces, inside the lightness band).

### Secondary
Green-grey `#4E6359` with container `#D9E8E0`: store avatars, quiet chips.

### Tertiary
Review amber `#855400` / container `#FFDDB4`: only for lines and receipts that need the user's attention.

### Neutral
Ground `#F6F7F5`, cards `#FFFFFF`, containers `#EAEEEB`/`#E4E9E5`, ink `#171D1A`, muted ink `#404944`.

### Named Rules
- **The One Accent Rule.** Only forint green carries emphasis; categories never get chart colours. Category identity
  is icon + name, with a 14 % tint container behind the icon as support only.
- **Status Is Never Colour Alone.** Every amber/red/green state ships with an icon and words
  ("Közeledsz a kerethez", "Túllépve", "Ellenőrizd").

## Typography

System Roboto through the Material type scale; sp units everywhere so the system font size applies.

### Hierarchy
- **Hero** (displayMedium 52 sp, 600, −1.2 sp): the month total on the dashboard. Exactly one per screen.
- **Headline** (headlineMedium 28 sp, 600): screen titles of top-level destinations.
- **Title** (titleMedium/Small, 600): section headers, store names, row titles.
- **Body** (bodyLarge/Medium): item names, descriptions.
- **Label** (labelMedium/Small): axis ticks, field labels.

### Named Rules
- **Tabular Columns.** Amounts that align vertically (receipt lines, lists, axis ticks) use tabular figures
  (`fontFeatureSettings = "tnum"`); the hero number keeps proportional figures.
- **No Eyebrows.** Headings carry their own weight; no kicker labels above them.

## Layout

Phone-first, one-handed: primary actions sit at the bottom (FAB "Blokk hozzáadása", review screen's sticky Mentés bar).
20 dp horizontal gutter, 28 dp above section headers, 8 dp below. Navigation bar with five destinations on compact
width; navigation rail with the FAB in its header from 600 dp. On wide screens content keeps an 840 dp measure, centered
(`MaxContentWidth`); only the camera, barcode, page-review and document viewer screens use the full width. Lists are
dense rows (min 56 dp) inside cards, not card grids.

## Elevation & Depth

Tonal surfaces plus a soft 1 dp card shadow (2 dp pressed); the review bottom bar uses 3 dp tonal + 6 dp shadow.
The receipt slip carries a 2 dp shadow that follows its perforated outline.

## Shapes

Rounded, friendly but restrained: cards 20 dp, fields 6 dp, chips/medium 14 dp, buttons fully rounded (Material
default). Chart bars have a 4 dp rounded data end and a square baseline; meters are fully rounded.

## Components

### Buttons
Filled (primary) for the one main action per screen, tonal for secondary, text buttons for inline actions. Minimum height 48 dp; 52 dp for screen-level actions.

### Chips
Filter chips for filters, modes and units; they wrap (FlowRow) instead of overflowing.

### Cards / Containers
`AppCard`: surface-lowest, 20 dp radius, 16 dp padding, 1 dp shadow. Never nested.

### Inputs / Fields
Outlined text fields; money fields accept Hungarian formats and show "Ft" as suffix; date fields open the Material date picker.

### Navigation
Material NavigationBar / NavigationRail with outlined → filled icon swap on selection.

### Receipt Slip (signature)
Receipt detail renders as a paper slip: centered store name, dashed rules, name/amount rows in tabular figures,
discounts in positive green, "ÖSSZESEN" with a headlineSmall total, monospace register IDs, perforated tear edge.

### Charts
Single-hue column chart (emphasis on current/peak period, value label only there), ranked horizontal bars for
part-to-whole (name, figure, share per row), 2 dp line with ringed end dot and 10 % wash, budget meter whose track is a
light step of its own hue. Tap shows an inverse-surface tooltip; every chart has a spoken summary.

## Do's and Don'ts

### Do:
- Show uncertainty: amber review markers, reconciliation lines ("eltérés 90 Ft"), unparsed lines kept for review.
- Keep money exact and formatted Hungarian style (`1 299 Ft`, narrow no-break group separator).
- Pair every status colour with an icon and words.
- Show long on-device work (OCR) with determinate progress and a text detail ("1/2. oldal · 64%"); a perpetual
  spinner redraws every frame and competes with recognition for the CPU.
- Round derived money figures (averages) to whole forints; keep item, unit and total amounts exact.

### Don't:
- Don't use donut/pie charts or colour-coded categories.
- Don't add eyebrow labels, gradient text or decorative glass.
- Don't present an OCR guess as fact; never fill a missing date or total silently.
