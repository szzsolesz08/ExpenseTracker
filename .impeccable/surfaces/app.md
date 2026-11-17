---
version: 1
slug: "app"
primary_target: "app"
related_targets: []
---

# Surface brief: KiadásKövető app (all screens)

Scope: native Android app, Operate mode (task completion), code-led build. Screens: onboarding,
dashboard (Áttekintés), receipts list/detail/review, scanner, processing, statistics, products,
budgets, categories, settings, backup.

## Direction contract

THESIS: The receipt is the unit of truth. Every number on screen can be traced back to a printed
line; the app refuses the category default of decorative KPI-card grids and donut charts.

OWN-WORLD: Thermal-paper whites and ink-dark numerals on a faintly green-grey ground (dark: deep
green-black). One accent, forint green (#0E7A5A light / #33A77C dark), used for the current period,
primary actions and positive state; amber and red reserved for review and over-budget status, always
with icon + label. Material 3 components, 20 dp card radius, soft offset shadows, Roboto with
tabular figures in item columns. Signature object: the receipt slip with a perforated tear edge.

STORY: The user sees this month's spending and how it compares, trusts it because uncertain lines are
flagged, and captures the next receipt with one thumb from the "Blokk hozzáadása" action.

FIRST VIEWPORT: Dashboard. Top: month name heading, hero amount (≥52 sp, count-up once), delta vs
previous month with direction icon and label, receipt count and average basket as one quiet line.
Below: budget meter when a budget exists, then ranked category bars (one hue). Extended FAB
"Blokk hozzáadása" bottom-right above the navigation bar, opening a bottom sheet with Fényképezés /
Kép importálása / PDF importálása.

FORM: Brief-pinned Material 3 fintech; seed: none (pinned) — "premium, modern, minimalist financial
dashboard", "Material 3", "Modern fintech aesthetic", "Rounded cards", "Subtle shadows".

FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance

Signature interaction: receipt detail renders as a paper slip with a perforated tear edge; lines
needing review carry an amber marker and open an edit sheet. Motion grammar: one authored moment —
hero amount count-up and bars growing from the baseline on first appearance; shared-axis transitions
between screens; all motion off when system animations are disabled.
