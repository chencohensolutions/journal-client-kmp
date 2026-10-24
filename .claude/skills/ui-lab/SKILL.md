---
name: ui-lab
description: Build UX/UI suggestions as tappable mocks in the journal client's UI lab, and promote the chosen one into the app once the owner approves a plan. Use whenever the owner asks to suggest, mock up, compare or try UX/UI designs for a screen.
---

# UI lab: UX suggestions

A UX suggestion goes through two phases:

1. **Mock** — only lab code changes, on this machine's platform. The owner taps through the options.
2. **Promote** — after the owner picks an option and approves a plan, it becomes the real screen, with its shared
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

Work on a `ux/<screen>` branch from the current one, so the other machine can pull the round and a rejected round
is dropped whole. If the tree has uncommitted changes that aren't yours, ask before switching.

### Files

Each option is split into what will ship and what is lab-only, all of it in the lab code:

| File (`.kt` / `.swift`) | Holds | On promotion |
|---|---|---|
| `<Screen>OptionA` | the screen, `<Screen>ScreenA(state, actions)`: production quality (strings from resources, `LibraryColors`) and free of lab types | moves into the app |
| `<Screen>ProposedState` | state the shared logic doesn't produce yet, shaped as the controller would produce it | moves to `sharedLogic` (from Swift, rewritten in Kotlin) |
| strings: Android `src/debug/res/values/strings.xml` and `values-iw/`; iOS `Lab/Lab.xcstrings` (`table: "Lab"`) | the option's new text | moves to the app's strings |
| `<Screen>Suggestions` | sample data, fake behavior, do-nothing actions, the `LabOption` entries | deleted |

- A mock that needs a changed version of an existing app component uses a copy in its option file.
- Sample data comes from `PreviewData` (Android) and `LabData` (iOS) as they are, plus whatever the option adds in
  its `Suggestions` file.

### Steps

1. Open the screen in the lab and look at `current`, the screen as the app has it now.
2. Write 2–3 distinct directions as options `a`, `b`, `c` after `current`, each with a one-line note on what it
   changes.
3. Above each `LabOption`, list what the option fakes: data the server or shared logic lacks, behavior that isn't
   built, and behavior made-up data can't show (timing, the photo library, the server).
4. Check the edge cases: Hebrew (RTL), long titles and notes, empty, offline, and whatever is unusual for the screen.
   A case that changes the design becomes its own option (`a-rtl`).
5. Build, open each option and look at a screenshot:
   - iOS: `xcrun simctl io <udid> screenshot <file>.png`. `open -a Simulator` fails on this Mac; the launch works
     anyway.
   - Android: `adb -s <serial> exec-out screencap -p > <file>.png`, with the serial the script printed. An
     all-black screenshot from the phone means it is locked: ask the owner to unlock it.
6. Commit on the branch and tell the owner the option ids (`<screen> <option>`), what each fakes, and the device.

When the owner asks to change an option, the change becomes a new option next to it (`b2` after `b`). Options the
owner rules out are deleted.

## Phase 2: promote

When the owner picks an option, enter plan mode (`EnterPlanMode`) and present the plan with `ExitPlanMode`; the
owner approves it with its button. The plan covers:

- the files that move and the screen they replace;
- the shared-logic work: the proposed state, controller methods and tests, and how each faked item gets built;
- the other platform: on the Mac the Android screen, on Windows an entry in `docs/ios-todo.md`;
- the `docs/screens/` updates.

After approval, on the same branch:

1. `git mv` the option file into the app (Android `src/main/.../ui/library/`; iOS `iosApp/iosApp/`, without its
   `#if DEBUG`), replacing the old screen, and give the function or view the screen's name.
2. Move its strings into the app's (Android `src/main/res/`; iOS `Localizable.xcstrings`, without `table: "Lab"`).
3. Move the proposed state into `sharedLogic`, have the controller produce it with tests, and connect the actions.
4. Fold the copied components back into the originals.
5. Delete the round's lab files; `current` now shows the new screen.
6. Build release, where any reference to lab code fails to compile: `./gradlew :androidApp:assembleRelease`, and on
   the Mac `xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Release -destination 'generic/platform=iOS Simulator' build`.
7. Do the plan's other-platform and `docs/screens/` parts.

Merging the branch is the owner's call.
