# App icon

The storyline entry (title, photo, paragraph) on navy `#1F2A44`, chosen by the owner on Oct 22, 2026. Options
explored: https://claude.ai/artifact/VczNxkhkog75nWbVgmEiEh

- `icon.svg`, `icon-dark.svg`, `icon-tinted.svg` are the iOS light, dark and tinted icons, drawn on a 100-unit grid.
  Remake the PNGs after a change:
  `for v in icon icon-dark icon-tinted; do rsvg-convert docs/app-icon/$v.svg -o iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/$v.png; done`,
  The light one renders without alpha, as the App Store wants.
- Android draws the same shapes as vectors in `androidApp/src/main/res/drawable/ic_launcher_*.xml` (background,
  foreground, monochrome for themed icons), scaled by 0.72 into the adaptive icon's visible 72dp. Change both together.
