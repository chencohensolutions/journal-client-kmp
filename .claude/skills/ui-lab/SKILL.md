---
name: ui-lab
description: Build UX/UI suggestions as tappable POC mocks in the journal client's UI lab. Use whenever the owner asks to suggest, mock up, compare or try UX/UI designs for a screen. On the Mac the mocks go in the iOS UI Lab app on the simulator; on Windows they go in the Android UI Lab app, on the owner's phone when it is connected over adb, else on the emulator.
---

# UI lab: UX suggestions as POC mocks

When the owner asks for UX or UI suggestions, build them as options in the UI lab, not as images, HTML or
artifacts. Full reference: [docs/previews.md](../../../docs/previews.md).

**A suggestion is a POC: one platform, UI only.** Build it only on this machine's platform (table below), and don't
touch `sharedLogic/`, the real screens, or the other platform. Anything the mock needs that doesn't exist yet (state
shapes, sample data, fake behavior) lives inside the suggestion file. Nothing beyond the mock is built until the owner
approves a plan.

## Pick the platform by machine

Check the platform first (`uname -s`, or the session's environment):

| Machine | Lab | Where the code goes | Open it |
|---|---|---|---|
| **Mac** (Darwin) | iOS **UI Lab** app on the simulator | `iosApp/iosApp/Lab/<Screen>Suggestions.swift`, listed in `Lab/LabScreens.swift` | `./scripts/ui-lab-ios.sh <screen> [option]` |
| **Windows** | Android **UI Lab** app on the owner's **phone** if `adb devices` lists it (USB or wireless), else the emulator | `androidApp/src/debug/kotlin/com/chencohensolution/journal/ui/library/<Screen>Suggestions.kt`, listed in `LabScreens.kt` | `./scripts/ui-lab.sh <screen> [option]` (Git Bash; picks the phone first, `DEVICE=emulator` to override) |

Use the other platform only when the owner asks for it.

Both UI Lab apps are separate from Journal (`com.chencohensolution.journal.uilab`), so opening the lab never replaces
the owner's Journal or stops its backup.

## Branch

Work on a `ux/<screen>` branch (from the current branch) and commit the suggestions there, so the other machine can
pull them and a rejected round is dropped without touching the main line. If the working tree has uncommitted
changes that aren't yours, ask before switching branches.

## Steps

1. Open the screen's options in the lab and look at `current` (the screen as the app has it now).
2. Propose 2–3 distinct directions, not variations of one. Write each as its own view/composable in
   `<Screen>Suggestions.*`, using the lab's made-up data, and add it to the screen's list as a `LabOption`
   **after** `current` (ids `a`, `b`, `c`; `current` always stays first). Its note says in a line what it changes.
   - Android: debug source set, same package as the screens (not a separate `lab` package). Read `PreviewData` /
     the lab data as they are; extra sample data goes in the suggestion file.
   - iOS: everything under `Lab/` is inside `#if DEBUG`. Read `LabData` as it is (it lives in `sharedLogic`, so don't
     edit it for a POC); extra sample data goes in Swift in the suggestion file.
3. **Mark what each mock fakes.** A comment at the top of each suggestion lists what it shows that the app can't
   produce today (data the server or shared logic doesn't have, behavior that isn't built), and the message to the
   owner says it too, so a design isn't picked for something that can't ship.
4. **Check the edge cases**, not only the neat sample data: Hebrew (RTL), long titles and notes, empty, offline, and
   whatever is unusual for the screen (many items, a missing picture). Look at each yourself; when a case changes
   the design, add it as its own option (`a-rtl`, `a-empty`) so the owner can see it.
5. Build and open each option, take a screenshot and check it yourself before showing the owner:
   - iOS: `xcrun simctl io <udid> screenshot <file>.png` (`open -a Simulator` fails on the Mac; the launch works anyway).
   - Android: `adb -s <serial> exec-out screencap -p > <file>.png` (the script prints the serial it used). An
     all-black screenshot from the phone means it is locked: ask the owner to unlock it, never try to yourself.
6. Commit on the branch and tell the owner the option ids (`<screen> <option>`), what each fakes, and on which device
   the lab is open.

## Iterating

When the owner asks for a change to an option ("b, but with X"), add it as a new option (`b2`) after `b` and keep
`b`, so the two can be compared. Remove the ones the owner rules out.

## When the owner picks one: plan first, then wait

Don't start implementing. Enter plan mode (`EnterPlanMode`) and present the plan with `ExitPlanMode`, so the owner
approves it with a button:

- what moves from the suggestion into the real screen on this platform;
- the shared-logic changes (state, controller methods, tests) the design needs, including each faked item from
  step 3 and what it takes to build for real;
- the other platform's work (on Windows: an entry in `docs/ios-todo.md`; on the Mac: the Android side);
- docs to update (`docs/screens/`), new strings, and which suggestions leave the lab.

Only after the owner approves, implement it (shared logic with tests, the real screen, the other platform or its
todo entry), and remove the chosen suggestion and the others from the lab, unless asked to keep them.
