# Agora Bidi Fix (LSPosed module)

Forces first-strong text-direction resolution (LTR fallback) inside Agora
(`com.newoether.agora`), so mixed Arabic/English chat bubbles resolve each
paragraph per the Unicode Bidirectional Algorithm instead of the locale default.

## Why

Agora renders messages with Jetpack Compose Markdown
(`app/.../ui/chat/message/MessageItemMarkdown.kt`,
`app/.../ui/chat/message/MessageBubbleAssets.kt`). The markdown `TextStyle`s
carry no explicit `textDirection`, so Compose falls back to the device locale
and RTL segments inside LTR paragraphs misalign (punctuation jumps sides,
Arabic inside English markdown renders backwards).

This module fixes it at runtime without touching Agora's source.

## What it hooks

- `android.text.StaticLayout$Builder` / `DynamicLayout$Builder` — `obtain()`
  binds `TextDirectionHeuristics.FIRSTSTRONG_LTR`; `setTextDirection()`
  replaces any *inferred* heuristic (`FIRSTSTRONG_*`, `ANYRTL_LTR`, `LOCALE`)
  with it; `build()` re-applies it. Explicit `LTR`/`RTL` forces are never
  touched, so phone/number/password fields keep working.
- `android.text.BoringLayout.make()` — same replacement for single-line labels.
- Compose `AndroidParagraphIntrinsics` constructors (both the current
  `androidx.compose.ui.text` package and the older `...ui.text.platform` one) —
  a `TextStyle` with `Unspecified`/`Content` direction is rewritten to
  `ContentOrLtr` (first-strong per paragraph, LTR fallback). Explicit styles
  stay untouched.

No characters are inserted, so link/spoiler/selection offsets stay valid.

## Install

1. Root the device and install LSPosed (or Vector) + its manager app.
2. Build this module (`:xposed-bidi` release APK) and install it.
3. Open the LSPosed manager → Modules → **Agora Bidi Fix** → enable it.
4. Scope is pre-declared (`scope.list` + manifest `xposedscope`): only
   **Agora** (`com.newoether.agora`). If the manager shows a scope list,
   make sure Agora is checked and nothing else is.
5. Force-stop Agora once (module loads at process start), then reopen it.

## Verify

Paste this into any chat and send:

```text
OK، تم إرسال الملف إلى الإدارة — report saved at /home/user/app v2.2.0!
```

Expected: the Arabic sentence reads right-to-left as one block, the trailing
`report saved at /home/user/app v2.2.0!` stays left-to-right at the line's
left end, and the `!` does not jump to the wrong side. Without the fix, the
leading `OK` flips the whole paragraph LTR and punctuation lands on the
wrong side.

Second probe (pure directions must not change):

```text
مرحبا بالعالم
Hello world
```

First line right-aligned (RTL), second left-aligned (LTR).

Diagnostics: `adb logcat -s AgoraBidiFix` shows install lines, hook counts,
and the first 20 direction rewrites.

## No-root alternative

Set the direction in-app instead: give the markdown typography an explicit
`TextDirection.ContentOrLtr` (see `roadmap/bidi-fix-libxposed.md`). Simpler,
no LSPosed needed — evaluate that first.
