# Product

<!-- impeccable:product-schema 1 -->

> Source: the user's written master brief (no interview was run — the user asked for no
> unnecessary questions). Facts marked *(inferred)* were derived from that brief, not confirmed.

## Platform

android

## Users

Hungarian-speaking individuals and households who shop at supermarkets (SPAR, INTERSPAR, Lidl,
ALDI, Tesco, PENNY and smaller shops) and want to know where their grocery money goes.
Typical moment *(inferred)*: right after shopping — at the checkout or at the kitchen table —
phone in one hand, thermal-paper receipt in the other; later, a calm review of the month.

## Product Purpose

KiadásKövető turns supermarket receipts (photo, image or PDF) into structured, categorized
purchase data on the phone and produces spending statistics, price history and budget warnings.
Success: a receipt is captured, reviewed and saved in under a minute, and the monthly picture
is trustworthy because every number comes from the user's own receipts.

## Positioning

Fully offline and private: OCR, receipt parsing, categorization and analytics all run on the
device. No account, no cloud, no API key, no INTERNET permission. It understands Hungarian
receipt conventions (VAT codes, weighed products, deposits/visszaváltási díj, cash rounding)
and never invents data it could not read — uncertain lines are flagged for review.

## Operating Context

- Capture: in-app camera (multi-shot for long receipts), image import, PDF import with page selection.
- Review: line-by-line confirmation of OCR results with confidence warnings, then save.
- Ongoing: monthly dashboard, statistics, product price history, store comparison, budgets.
- One-handed phone use; light and dark environments (store lighting, evening at home).

## Capabilities and Constraints

- Native Android (Kotlin, Jetpack Compose, Material 3), minSdk 29, Hungarian UI.
- Money is exact (integer fillér); financial arithmetic is deterministic.
- Optional local LLM: model import and inspection only; no inference runtime is bundled in this build.
- Brief-pinned terminology: Áttekintés, Blokkok, Statisztika, Termékek, Beállítások, "Blokk hozzáadása".

## Brand Commitments

- Name: KiadásKövető. Language: Hungarian everywhere.
- Brief-pinned look: "premium, modern, minimalist financial dashboard", "Material 3", "modern
  fintech aesthetic", clean typography, rounded cards, subtle shadows, carefully selected accent
  colors, smooth animations, dark and light mode, "must not look like a generic default Android interface".

## Evidence on Hand

No real user data, testimonials or brand assets exist. Sample receipts are test fixtures only and
must never appear in the shipped app's reports; first-run screens show honest empty states.

## Product Principles

1. Never invent receipt data; show uncertainty instead of hiding it.
2. The user's data never leaves the phone unless the user exports it.
3. Capture must be fast; review must be precise.
4. Numbers are exact and explainable down to the receipt line.

## Accessibility & Inclusion

48 dp touch targets, sp type that follows the system font scale, WCAG AA contrast in both themes,
meaning never carried by color alone (icons + labels for status) *(inferred from the brief's
"accessible contrast and font scaling")*.
