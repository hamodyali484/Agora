# BiDi Fix Module (libxposed) — Report

## Problem

Agora renders mixed-direction text (e.g. Arabic/Hebrew inside LTR English
markdown) incorrectly in chat bubbles. Jetpack Compose `Text` and the
Markdown renderer do not always apply the Unicode Bidirectional Algorithm
per-paragraph, so RTL segments appear misaligned or punctuation lands on the
wrong side.

Relevant rendering code:

- `app/src/main/java/com/newoether/agora/ui/chat/message/MessageItem.kt` —
  message bubble layout (delegates rendering to the files below)
- `app/src/main/java/com/newoether/agora/ui/chat/message/MessageItemMarkdown.kt` —
  message bubble rendering via `Markdown(...)` and `LatexAwareText(...)`
  (`LatexAwareMarkdownText` → `AnimatedMarkdownText` → `MarkdownText`)
- `app/src/main/java/com/newoether/agora/ui/chat/message/MessageBubbleAssets.kt` —
  `rememberChatMarkdownAssets()` builds the `MarkdownTypography` from
  `scaledMarkdownTextStyle(ChatType.body / mdH1… / code / …)`; none of these
  styles sets `textDirection`, so Compose resolves it from the device locale
- `app/src/main/java/com/newoether/agora/ui/components/LatexRenderer.kt` —
  span splitting / paragraph grouping (LaTeX spans, markdown escaping)
- `app/src/main/AndroidManifest.xml` — `supportsRtl="true"` already set

Root cause: every markdown `TextStyle` has `textDirection = Unspecified`, so
direction falls back to the locale instead of first-strong per paragraph.

## Proposed solution: libxposed module

Build an Xposed module using libxposed (LSPosed API) that hooks text layout
at runtime to enforce correct BiDi handling per-app, without modifying
Agora's source. Implemented in `:xposed-bidi` (see `xposed-bidi/README.md`).

### Scope

1. New Gradle module `:xposed-bidi` depending on
   `io.github.libxposed:api:101.0.1` (compile-only; 101 because the project
   builds against `compileSdk 36`, and api 102 needs 37).
2. Hook targets (framework text stack — covers Compose too, since
   `AndroidParagraph` lays out through `StaticLayout.Builder`/`BoringLayout`
   internally):
   - `android.text.StaticLayout$Builder` / `DynamicLayout$Builder` —
     `obtain()` binds `FIRSTSTRONG_LTR`; `setTextDirection()` replaces
     `FIRSTSTRONG_*` / `ANYRTL_LTR` / `LOCALE` with it; `build()` re-applies
     it. Explicit `LTR`/`RTL` forces are never touched.
   - `android.text.BoringLayout.make()` — same replacement for single-line
     text (titles, cells, hints).
3. Scoped to `com.newoether.agora` only (`scope.list`, `staticScope=true`,
   manifest `xposedscope`, plus a runtime package check in `Entry`).

### Deliverables

- `xposed-bidi/` module with `Entry` implementing `XposedModule`.
- `xposed_init` asset (legacy) + `META-INF/xposed/` metadata (`java_init.list`,
  `module.prop`, `scope.list`) + manifest metadata (`xposedmodule`,
  `xposedscope`, `xposedminversion`).
- Hook implementation for `android.text.Layout` direction resolution
  (`BidiHooks.java`: builders + BoringLayout).
- README: install via LSPosed, enable scope, verify with mixed
  Arabic+English sample text (`xposed-bidi/README.md`).

### Risks / open questions

- Compose text uses its own layout pipeline (`AndroidParagraph`), which
  resolves direction from `TextStyle.textDirection` before reaching the
  framework stack. The markdown typography sets no explicit direction, so the
  framework-level `FIRSTSTRONG_LTR` still decides per paragraph — but verify
  with logcat (`adb logcat -s AgoraBidiFix`) on the shipped Compose BOM
  (`2026.05.01`). If Compose ever bypasses the framework stack, the fallback
  is hooking the `AndroidParagraphIntrinsics` constructors (internal,
  R8-renamed — locate by signature, both `androidx.compose.ui.text` and
  legacy `...ui.text.platform` packages).
- Alternative without root: fix in-app (below) — simpler, no LSPosed needed.
  Recommend evaluating this first.

## Non-root alternative: `TextDirection.ContentOrLtr` in markdown typography

One-spot fix, no module needed. In `MessageBubbleAssets.kt`, every markdown
style flows through `scaledMarkdownTextStyle()`:

```kotlin
internal fun scaledMarkdownTextStyle(style: TextStyle): TextStyle = style.copy(
    ...
    textDirection = TextDirection.ContentOrLtr,
)
```

with `import androidx.compose.ui.text.style.TextDirection`. This covers body,
paragraph, list, headings, code and table styles (all built via
`scaledMarkdownTextStyle`), for both the main and thought typographies.
`ContentOrLtr` = first-strong character decides per paragraph, LTR fallback
when no strong character exists — exactly the Unicode BiDi behaviour the
module enforces from outside. Explicit `LTR`/`RTL` usages elsewhere
(phone/number fields) are untouched since only the markdown styles change.
