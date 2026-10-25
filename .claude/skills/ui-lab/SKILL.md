---
name: ui-lab
description: Build UX/UI suggestions as tappable mocks in the journal client's UI lab, record each round in docs/ux-history, and promote the chosen option into the app once the owner approves a plan. Use whenever the owner asks to suggest, mock up, compare or try UX/UI designs for a screen, or to bring back an earlier suggestion.
---

# UI lab: UX suggestions

A UX suggestion goes through three phases:

1. **Mock** — only lab code changes, on this machine's platform. The owner taps through the options.
2. **Close the round** — the round is tagged and written up in `docs/ux-history/`, and its lab files are deleted.
3. **Promote** — after the owner picks an option and approves a plan, it becomes the real screen, with its shared
   logic and the other platform.

How the lab itself works: [docs/previews.md](../../../docs/previews.md).

## Platform

| Machine (`uname -s`) | Lab app | Lab code | Open |
|---|---|---|---|
| Mac (Darwin) | iOS UI Lab on the simulator | `iosApp/iosApp/Lab/` | `./scripts/ui-lab-ios.sh <screen> [option]` |
| Windows | Android UI Lab on the owner's phone when `adb devices` lists it, else the emulator | `androidApp/src/debug/` | `./scripts/ui-lab.sh <screen> [option]` in Git Bash (`DEVICE=emulator` or `DEVICE=<serial>` to choose) |

Mocks go on the other platform when the owner asks for it. Both UI Lab apps (`com.chencohensolution.journal.uilab`)
install beside Journal, which keeps running and backing up.

## Phase 1: mock

Work on a `ux/<screen>` branch from the current one, so the other machine can pull the round. If the tree has
uncommitted changes that aren't yours, ask before switching.

### One folder per option

Each option lives in its own folder, so it can be deleted, revived or promoted alone:

- Android: `androidApp/src/debug/kotlin/com/chencohensolution/journal/ui/library/lab/<screen>/<b>/`, in the
  screens' package (`com.chencohensolution.journal.ui.library`). Its strings files are in `src/debug/res/values/`
  and `values-iw/`, since Android resources can't sit in subfolders.
- iOS: `iosApp/iosApp/Lab/<Screen>/Option<B>/`, strings included.

| File (`.kt` / `.swift`) | Holds | On promotion |
|---|---|---|
| `<Screen>Option<B>` | the screen, `<Screen>Screen<B>(state, actions)`: production quality (strings from resources, `LibraryColors`) and free of lab types | moves into the app |
| `<Screen>Option<B>State` | state the shared logic doesn't produce yet, shaped as the controller would produce it | moves to `sharedLogic` (from Swift, rewritten in Kotlin) |
| `<Screen>Option<B>Lab` | its sample data, fake behavior, do-nothing actions, and its `LabOption` value with what it fakes above it | deleted |
| strings: Android `strings_<screen>_<b>.xml` (names prefixed `<screen>_<b>_`); iOS `<Screen>Option<B>.xcstrings` (`table: "<Screen>Option<B>"`) | the option's new text | moves to the app's strings, without the prefix or table |

- Besides the folder and Android's strings files, an option adds one line: its entry in its screen's list in
  `LabScreens` (`momentsOptionB,`).
- Everything in the folder is `private` except the screen, its state and the `LabOption` value, whose names carry
  the option, so options never clash.
- An option that needs a changed version of an existing app component uses its own copy, in its folder.
- Sample data comes from `PreviewData` (Android) and `LabData` (iOS) as they are, plus what the option adds in its
  `Lab` file.

### Steps

1. Open the screen in the lab and look at `current`, the screen as the app has it now.
2. Write 2–3 distinct directions as options `a`, `b`, `c` after `current`, each with a one-line note on what it
   changes.
3. Above each `LabOption` value, list what the option fakes: data the server or shared logic lacks, behavior that
   isn't built, and behavior made-up data can't show (timing, the photo library, the server).
4. Check the edge cases: Hebrew (RTL), long titles and notes, empty, offline, and whatever is unusual for the screen.
   A case that changes the design becomes its own option (`a-rtl`).
5. Build, open each option and look at a screenshot:
   - iOS: `xcrun simctl io <udid> screenshot <file>.png`. `open -a Simulator` fails on this Mac; the launch works
     anyway.
   - Android: `adb -s <serial> exec-out screencap -p > <file>.png`, with the serial the script printed. An
     all-black screenshot from the phone means it is locked: ask the owner to unlock it.
6. Commit on the branch and tell the owner the option ids (`<screen> <option>`), what each fakes, and the device.

When the owner asks to change an option, the change becomes a new option in its own folder (`b2` beside `b`).
Options the owner rules out stay until the round closes, so the round's tag has them all.

## Phase 2: close the round

When the owner has picked an option or turned them all down:

1. Tag the round's last commit, while every option is still in it, and push the tag:
   `git tag ux/<screen>/<yyyy-mm-dd> && git push origin ux/<screen>/<yyyy-mm-dd>`. The tag keeps the code reachable
   after the branch is gone; a second round the same day gets `-2`.
2. Write `docs/ux-history/<screen>/<yyyy-mm-dd>-<topic>.md` in the format below, and link it from the screen's doc
   in `docs/screens/` under "Design history".
3. Delete the other options' folders, strings files and `LabScreens` lines; the chosen option stays until phase 3.
4. Commit. With every option turned down, the round ends here: merge the branch (the history file is its only
   lasting change) or delete it, as the owner prefers.

```markdown
# <Screen> — <topic> (<yyyy-mm-dd>, <iOS|Android>)

Tag: `ux/<screen>/<yyyy-mm-dd>`

## Options
- **a — <name>**: <what it changes>. *Picked*, promoted in <commit>.
- **b — <name>**: <what it changes>. *Turned down*: <the owner's reason, when given>.

## What they faked
- b: <what the mock showed that the app can't do yet>.

## Reviving an option
    git checkout ux/<screen>/<yyyy-mm-dd> -- <option folder> <strings files>
Then add `<screen>Option<B>,` to `LabScreens`.
Built against <components and sample data it used> as of the tag. Compile errors after reviving are in the
option's copies and sample data; fix them there.
```

### Reviving

When the owner asks for an earlier option, follow its history file: check out the paths from the tag onto a
`ux/<screen>` branch, add the `LabScreens` line, fix what no longer compiles inside the option's folder, and open it.
It is then a live option of a new round.

## Phase 3: promote

With the round closed on a picked option, enter plan mode (`EnterPlanMode`) and present the plan with
`ExitPlanMode`; the owner approves it with its button. The plan covers:

- the files that move and the screen they replace;
- the shared-logic work: the proposed state, controller methods and tests, and how each faked item gets built;
- the other platform: on the Mac the Android screen, on Windows an entry in `docs/ios-todo.md`;
- the `docs/screens/` updates.

After approval, on the same branch:

1. `git mv` the option's screen file into the app (Android `src/main/.../ui/library/`; iOS `iosApp/iosApp/`, without
   its `#if DEBUG`), replacing the old screen, and give the function or view the screen's name.
2. Move its strings into the app's (Android `src/main/res/`; iOS `Localizable.xcstrings`), without the prefix or
   table.
3. Move its state into `sharedLogic`, have the controller produce it with tests, and connect the actions.
4. Fold its copied components back into the originals, and delete the rest of its folder and its `LabScreens` line.
5. Build release, where any reference to lab code fails to compile: `./gradlew :androidApp:assembleRelease`, and on
   the Mac `xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Release -destination 'generic/platform=iOS Simulator' build`.
6. Do the plan's other-platform and `docs/screens/` parts, and put the promoting commit in the round's history file.

Merging the branch is the owner's call.
