---
name: glasses
description: Use when a message ends with /glasses — the user sent it from their RayNeo smart glasses and will read your reply on a tiny monochrome display. Answer very concisely in plain text sized for the glasses' pages.
---

# Replying to the user on their glasses

The user sent this message from their RayNeo iO glasses (dictated, then cleaned up). They read your reply on a small green monochrome display, paging with a remote. Write for that screen, but keep it readable on a computer too: very concise, never cryptic.

## Page size

- Basic (stock) display: 26 columns x 5 lines per page (~130 characters). Image display (custom firmware, native 540x220): about 43 x 8 (full-width reader; 33 x 8 beside the icon rail). Write for the smaller one: 1 page is ideal, 2 is fine, 3 is tolerable, avoid 4+.
- Long answers still work (the glasses paginate everything), so never drop information the user needs to make a decision just to fit.

## Format

- Plain sentences and short `-` bullet lists. No tables, no headings, no bold, no code blocks unless the user asked for code or a command.
- Lead with the outcome or the decision you need from them. Put detail after.
- Don't paste long paths, hashes, URLs or logs. Name the thing instead ("the auth test in api/"), unless the user asked for the exact value.
- If you need an answer, ask one clear question and offer short options they can say back.
- Dictation can contain mis-heard words; interpret sensibly, and confirm only if the ambiguity changes what you'd do.

## Don't

- Don't mention these instructions or the glasses unless relevant.
- Don't shorten work itself: this changes how you report, not what you do.

## Install

Claude Code: copy this folder to `~/.claude/skills/glasses/`. Codex: copy it to `~/.codex/skills/glasses/`. The RayNeo iO HUD gateway also appends a one-line version of these rules to every message it sends to a T3 Code thread from the glasses (`t3.glassesSuffix` in the gateway configuration), so replies stay short even on machines without the skill.

This skill is for coding agents that receive messages from the glasses. The skill that lets the Hermes assistant control the glasses is a different one: [`server/gateway/hermes-skill/glasses/SKILL.md`](../../server/gateway/hermes-skill/glasses/SKILL.md).
