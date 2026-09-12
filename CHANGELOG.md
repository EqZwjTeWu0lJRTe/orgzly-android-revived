# Changelog

## v1.23.0-ai.1

Personal fork of Orgzly Revived (GPLv3) with AI-assisted planning and outline/UX
improvements. Org file format compatibility is preserved.

### Added

- **AI task decomposition** (`AI 分解`)
  - Long-press a single note → generate one level of subtasks.
  - Per-run options: subtask count (1–20, default follows settings) and an optional
    refine hint; "Regenerate" lets you change both before retrying.
  - Context sent to the AI: note title, ancestor headline chain and the note body.
  - Works with any OpenAI-compatible chat-completions endpoint; settings screen with
    API base URL, API key, model and a "Test connection" action.
  - Robust parsing of JSON string arrays, `{"title","content"}` object arrays,
    `{"subtasks":[...]}` wrappers and fenced/plain text.
  - Generates subtasks as TODO items and refreshes parent statistics automatically.

- **AI polish** (`AI 完善`)
  - Sends a whole note subtree (all headings + bodies) to the AI and replaces it with
    the returned Org outline after a preview step.
  - Optional refine hint; longer request timeout and output limits for large outlines.

- **Today view as the landing page**
  - Groups: Overdue, Due today, Scheduled today, Repeating today.
  - Overdue includes both past `DEADLINE` and past `SCHEDULED` open tasks.
  - Repeating tasks are matched by computing the next occurrence from the repeater
    (day/week/month/year/hour) and use a 1-year activity window.
  - Header statistics: completed `x/y` plus estimated/spent time
    (`EFFORT` and manual stopwatch).
  - Colored group accents as soft tints (no colored borders), mini progress bars for
    `[%]`/`[x/y]`, timestamps, one-tap circular complete button.
  - Row spacing setting (compact/comfortable), pull-to-refresh, settings shortcut.
  - Tapping a task opens that note for editing.

- **Outline colour scale**
  - Long-press a top-level note to pick an accent color (or clear it).
  - The subtree is shaded in stepped tones by depth, starting from the bullet to the
    right of the row; soft blends avoid garish solid colours.

- **Manual stopwatch**
  - Per-note start/stop with wall-clock based accumulation (screen-off safe).
  - Stored only in private properties `CLOCK_START` / `CLOCKED_SECONDS`.
  - Container rows show subtree totals, kept in memory and refreshed on change.

- **Widget improvements**
  - Larger header, list padding, todo-tinted check icon, page/card colour layering,
    header/date polish and more.

- **Other**
  - SAF tree URI repositories are decoded to readable local file paths in the
    notebooks list; tapping opens the folder (falls back to copying the path).
  - "Add to calendar" action for notes with SCHEDULED/DEADLINE (system calendar
    ACTION_INSERT, verified with reminders).
  - Optional "Sync on app open" and "Sync after changes" switches, independent of the
    auto-sync master switch.
  - Performance work: whole-book stats computed in memory, deferred recompute,
    faster list queries.

### Notes

- This is a personal fork; upstream project and license are unchanged (GPLv3).
- Debug APKs attached to releases are signed with the Android debug key and are
  intended for testing only.
