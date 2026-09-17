# Fix BlockActivity "Continue" and Grace Period Re-trigger (Two-Part Bug)

## Bug summary

- **Bug 1:** On BlockActivity shown after grace period ends, tapping "Continue to app" opens Chillpill MainActivity instead of the app the user was using.
- **Bug 2:** After that, the block does not trigger again when the next grace period ends; the grace period effectively does not start again.

## Desired behavior: "Continue" = switch back to the app

Returning from the block screen should be **equivalent to quickly visiting another app without closing the previous one, then switching back**: the user should return to the **same task**, **same activity**, and **same in-app state** when the system has kept that task alive. We do not want to "launch" the app from scratch (which can clear the task or create a new instance); we want to **bring the existing task to the front**.

- **Same app + same activity:** Use the monitored app’s package and, when available, the exact activity class they were in (`className`).
- **Same state:** Use intent flags that **bring the existing task to front** instead of starting a new instance or clearing the task. If the task was still in the back stack and not destroyed, the user gets back the same activity and state. If the system had killed the task (e.g. low memory), the activity will be recreated and state depends on the app’s own save/restore; we cannot restore it from Chillpill.

---

## Root cause analysis

### Bug 1: Why MainActivity appears instead of the target app

[AppBlockActivity.kt](app/src/main/java/com/chillpill/AppBlockActivity.kt) already implements `launchTargetAppAndFinish()`. When BlockActivity is started by **GracePeriodService**, `className` can be missing (accessibility event had null class). Then we use `getLaunchIntentForPackage(packageName)`, which can return **null** for some apps. When `launchIntent` is null we never call `startActivity()`; we only `finish()`, so Chillpill’s task (MainActivity) is shown.

### Bug 2: Why the block doesn’t trigger again

The grace period is started only when the accessibility service sees a transition **from Chillpill (block) to the monitored app**. After Bug 1 the user stays in Chillpill (MainActivity), so that transition never happens, `tryHandleReturnFromBlock` is never called, and `GracePeriodService` is never started. Fixing Bug 1 (so the user actually returns to the monitored app) fixes Bug 2.

---

## Fix strategy

1. **Robust launch:** Ensure we always have a valid intent to "go to" the monitored app (fallback chain: `className` → `getLaunchIntentForPackage` → launcher query).
2. **Switch-back semantics:** When building that intent, use **only `FLAG_ACTIVITY_NEW_TASK`** and **do not use `FLAG_ACTIVITY_CLEAR_TOP`**. That way the system will bring the **existing task** for that app to the front (if it exists) instead of clearing the task or creating a new instance. Result: same task, same activity, state preserved when the task was kept alive.
3. **Optional:** Persist last-known activity in `BlockingSharedState` so GracePeriodService can pass `className` when starting the block, making the "same activity" case more reliable.

---

## Implementation plan

### 1. Harden `launchTargetAppAndFinish()` and use switch-back flags (Bug 1 + desired UX)

**File:** [AppBlockActivity.kt](app/src/main/java/com/chillpill/AppBlockActivity.kt)

**Intent flags:**

- Use **`FLAG_ACTIVITY_NEW_TASK` only** (remove `FLAG_ACTIVITY_CLEAR_TOP`) when starting the target app on "Continue to app". This brings the app’s existing task to the front without clearing activities on top of the target activity, so returning is like switching back from recents.
- Apply this to both the `setClassName(packageName, className)` path and the `getLaunchIntentForPackage` / launcher-fallback path.

**Fallback chain (unchanged in logic, same flags):**

1. If `EXTRA_CLASS_NAME` is present and non-blank: `Intent().setClassName(packageName, className)` with `FLAG_ACTIVITY_NEW_TASK` only.
2. Else: `packageManager.getLaunchIntentForPackage(packageName)` with `FLAG_ACTIVITY_NEW_TASK` only.
3. If still null: build launcher intent `Intent(ACTION_MAIN).addCategory(CATEGORY_LAUNCHER).setPackage(packageName)`, resolve with `packageManager.resolveActivity()`, and if resolved use that component with `FLAG_ACTIVITY_NEW_TASK` only.

Then `startActivity(launchIntent)` when non-null, and always `finish()` (with try/catch and log on failure).

**Clarification in code/doc:** Returning from the block screen is intended to be equivalent to visiting another app and switching back; we do not guarantee state if the system has killed the task.

### 2. (Optional) Ensure `className` is available when block is started by GracePeriodService

- In [BlockingSharedState.kt](app/src/main/java/com/chillpill/service/BlockingSharedState.kt): add something like `lastKnownActivityForPackage` (package → class name).
- In [ChillpillAccessibilityService.kt](app/src/main/java/com/chillpill/service/ChillpillAccessibilityService.kt): when we have a monitored app and non-null `className`, update shared state.
- In [GracePeriodService.kt](app/src/main/java/com/chillpill/service/GracePeriodService.kt): when building the BlockActivity intent, if the grace Intent has no `className`, set `EXTRA_CLASS_NAME` from `BlockingSharedState.getLastKnownActivityForPackage(packageName)` if present.

This helps "Continue" target the exact same activity more often.

### 3. No separate change for Bug 2

Once "Continue" reliably brings the monitored app’s task to the front, the accessibility service will see Chillpill → monitored app and start the grace period again.

---

## Files to modify

| File | Change |
|------|--------|
| [AppBlockActivity.kt](app/src/main/java/com/chillpill/AppBlockActivity.kt) | In `launchTargetAppAndFinish()`: (1) Use **only `FLAG_ACTIVITY_NEW_TASK`** (remove `FLAG_ACTIVITY_CLEAR_TOP`) for the target-app intent so returning is like switching back. (2) Add launcher-intent fallback when `getLaunchIntentForPackage` is null (resolve `ACTION_MAIN` + `CATEGORY_LAUNCHER` + `setPackage`, use resolved component). (3) Optional: short comment that "Continue" is intended to behave like switching back to the app. |
| (Optional) [BlockingSharedState.kt](app/src/main/java/com/chillpill/service/BlockingSharedState.kt) | Add `lastKnownActivityForPackage` and getter/setter. |
| (Optional) [ChillpillAccessibilityService.kt](app/src/main/java/com/chillpill/service/ChillpillAccessibilityService.kt) | When we have monitored app + non-null `className`, set `BlockingSharedState.setLastKnownActivityForPackage(pkg, className)`. |
| (Optional) [GracePeriodService.kt](app/src/main/java/com/chillpill/service/GracePeriodService.kt) | When building BlockActivity intent, if no `className` in grace Intent, set `EXTRA_CLASS_NAME` from `BlockingSharedState.getLastKnownActivityForPackage(packageName)` if present. |

---

## Verification

- **Switch-back behavior:** Trigger block after grace expiry, tap "Continue to app". Confirm the monitored app comes to front and, when the task was not killed, the same activity and in-app state are preserved (e.g. same screen, scroll, form data).
- **Bug 1:** With an app that could previously yield null launch intent, confirm "Continue" opens that app (or its launcher) instead of Chillpill MainActivity.
- **Bug 2:** After "Continue", let grace expire again (or leave and re-enter as per spec); confirm the block appears again and the grace/re-block cycle continues.
