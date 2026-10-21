# Screen mockups

Clickable mockups of every screen, as plain HTML files. Open [index.html](index.html) in a browser (double-click
it; no server or build) to see all screens side by side, with a light/dark switch. Each screen also opens on its own,
and the screens link to each other like the app.

They follow the specs in [docs/screens/](..). When a spec changes, change its mockup in the same commit.

**Out of date:** `journal-home.html` and `viewer.html` still show stories (story cards, + Story, Add to story, the
Stories and Group sections), which were replaced by the storyline on Oct 21, 2026, and the home mockup predates the
Moments feed. Follow [journal-home.md](../journal-home.md) and [viewer.md](../viewer.md) until they are redrawn. The
story and group mockups were deleted.

| File | Screen | Try |
|---|---|---|
| `photo-access.html` | [Photo access](../photo-access.md) | Allow access, Not now |
| `journal-home.html` | [Journal](../journal-home.md) (out of date) | ⋯, the tabs |
| `viewer.html` | [Viewer](../viewer.md) (out of date) | ⓘ, + Tag |
| `all-photos.html` | [All photos](../all-photos.md) | the filter bar |
| `tags.html` | [Tags](../tags.md) | search, open a tag |
| `settings.html` | [Settings](../settings.md) | By breaks between photos |

URL options: `?theme=light|dark`, and on some screens `?offline`, `journal-home.html?tab=unsorted`,
`?card=register|denied|limited`, `?grouping=breaks`, `viewer.html?from=tag|all&info`.

## How they're built

- One HTML file per screen, a shared [mock.css](mock.css) (colors as variables, light and dark) and
  [mock.js](mock.js) (icons, sample data, the photo tile).
- [petite-vue](https://github.com/vuejs/petite-vue) 0.4.1 (MIT, in `vendor/`) for the interactive bits: `v-if`,
  `v-for`, `{{ }}` and `@click` in the HTML, state in a `mount({...})` call at the bottom of each page.
- Photos are colored placeholders. The DM Sans and Newsreader fonts load from Google Fonts; offline the system
  font is used.
- petite-vue can't put a `v-for` directly inside `<template v-if>`; use `<div class="contents" v-if>` instead.
