# AGENTS.md — Agora Arabic fork

This repository is our own maintained copy of **[newo-ether/Agora](https://github.com/newo-ether/Agora)**
(Android Kotlin/Jetpack Compose LLM client, BYOK, multi-provider). We keep the Arabic/RTL fixes
alive here and ship our own signed **arm64** APK, because upstream may never merge them.

Working copy on this machine: `~/files/ideas/Agora`.
Our fork: `hamodyali484/Agora`.

## Branches

| Branch | Purpose |
| --- | --- |
| `arabic` | **Maintained branch.** Upstream `master` + the RTL work + this file, `ARABIC_BASE` and the release workflow. Every release is built from here. |
| `fix/arabic-rtl` | Clean patch series opened upstream as [PR #227](https://github.com/newo-ether/Agora/pull/227). Keep it free of our private files so the PR stays reviewable. |
| `master` | Older fork master with the experimental `xposed-bidi` module (runtime bidi hooks). Not part of our builds. |
| `upstream/master` | Mirror of the original project, read-only. |

## Remotes

- `origin` = `git@github-484:hamodyali484/Agora.git` — the SSH host alias `github-484`
  (`~/.ssh/id_github_484`) is the **hamodyali484** account.
- `upstream` = `https://github.com/newo-ether/Agora`.
- Plain `git@github.com` authenticates as a different account and **cannot push here**. Use the
  `github-484` alias, or an HTTPS token from `gh auth token -u hamodyali484`.

## What we changed (and must stay working)

Root cause of every reported symptom: Compose takes a paragraph's base direction — and therefore its
alignment, ellipsis side, cursor/selection behaviour and the placement of trailing punctuation and
digits — from the **UI layout direction**, which follows the app language. With the app in English,
Arabic/Hebrew content is laid out as an LTR paragraph. The fix derives direction from the content.

1. `app/src/main/java/com/newoether/agora/util/ContentTextDirection.kt` — `isRtlContent()`,
   `contentTextDirection()`, `rememberContentTextDirection()`: the `dir="auto"` rule (first strong
   directional character wins; digits, punctuation, whitespace and emoji are neutral).
2. Messages (`ui/chat/message/**`): user bubbles, assistant markdown (per paragraph), streaming
   markdown, error/terminal bars, tool and file detail bodies, citations follow their content
   direction. Technical surfaces — fenced and inline code, JSON, shell output, LaTeX, tables, URLs,
   file paths — are pinned LTR so surrounding Arabic cannot flip them.
3. Conversations drawer and top bar (`ui/chat/ChatDrawerHost.kt`, `ChatDrawerContent.kt`,
   `ChatTopBar.kt`, `chat/search/**`): anchors, drag-settle velocity sign, placement and the
   side-by-side padding follow the layout direction (drawer opens from the start edge); titles,
   previews and search results use the content direction.
4. Composer and interaction bars (`ui/chat/bottombar/**`, `ui/chat/interaction/**`): the input takes
   its direction from the typed text and falls back to the UI direction while empty; mirrored
   glyphs; direction-aware scrollbar thumb and composition-bar segments. Row widths are
   deliberately untouched — existing layout tests assert natural widths.
5. Settings, onboarding, remote control, tasks and shared components: user content (names, prompts,
   labels, server/device names) follows its own direction; numbers, units, paths, model ids and API
   keys stay LTR; the phone-use page's forced-LTR scope is narrowed to the LTR-only slider.
6. `phone/PhoneOverlayLabels.kt`, `MainActivity.kt`, `util/AppLocale.kt`: overlay status labels follow
   content direction; the effective locale is published for the "system" language choice too.

### Rules when touching UI here

- Never align or order user/model content by `LocalLayoutDirection` — use
  `rememberContentTextDirection` on the text that is actually displayed.
- Keep machine-readable content LTR (code, paths, URLs, JSON, ids, keys).
- Do not restructure row widths/weights just to "fix" direction: it breaks the existing layout tests.
- Add a test next to the behaviour (`ContentTextDirectionTest`, `PhoneStatusCapsuleDirectionTest`,
  `ContextCompositionBarTest` are the existing patterns).

## Pulling upstream updates

```bash
cd ~/files/ideas/Agora
git fetch upstream master
git switch arabic
git rebase upstream/master          # on conflicts keep the RTL logic, never drop it
git rev-parse upstream/master > ARABIC_BASE
```

Verify, then publish:

```bash
GRADLE_USER_HOME=/home/t-space/.gradle-agora ANDROID_HOME=/opt/android-sdk \
  ./gradlew :app:compileFdroidDebugKotlin \
    :app:testFdroidDebugUnitTest \
    --tests "com.newoether.agora.ui.*" \
    --tests "com.newoether.agora.phone.*" \
    --tests "com.newoether.agora.util.*"

git push --force-with-lease origin arabic
gh workflow run arabic-release.yml --repo hamodyali484/Agora --ref arabic
```

Then download the release asset and hand it over.

Machine notes:

- Always build with `GRADLE_USER_HOME=/home/t-space/.gradle-agora`; the default
  `~/.gradle/init.d/mirrors.gradle` empties `pluginManagement.repositories`, so KSP fails to resolve
  from the usual home.
- `chmod +x gradlew` is required (the repository ships it mode 644).
- Release builds need `ANDROID_HOME=/opt/android-sdk` and NDK `28.2.13676358`.

## Release builds

- Workflow: `.github/workflows/arabic-release.yml` — `workflow_dispatch` or tags `arabic-*`.
- Output is a **GitHub Release asset** `Agora-arabic-<version>-<tag>-arm64-v8a.apk`, not a workflow
  artifact.
- arm64 only: `defaultConfig.ndk.abiFilters` is `arm64-v8a`; the `debug` build type is x86_64, so
  always build `assembleFdroidRelease`.
- Signing: our dedicated keystore lives outside the repo at
  `~/files/ideas/agora-keys/agora-arabic-release.jks` (with `credentials.txt`, mode 600). Repo
  secrets on `hamodyali484/Agora`: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`
  (`agora-arabic`), `KEY_PASSWORD`.
- Keystore certificate SHA-256:
  `EB:E7:B8:4C:E8:95:09:00:9B:9D:9F:62:F3:22:CE:D8:9A:88:6C:AF:6B:6A:89:B5:FE:79:C7:C1:E0:85:3A:F5`
- Never commit the keystore — losing it means an installed build can never be updated over the air.
- An APK signed with a different key cannot be installed over ours; uninstall the old one first.

## Upstream PR

- [PR #227](https://github.com/newo-ether/Agora/pull/227): `fix/arabic-rtl` → upstream `master`.
- If upstream merges it (or an equivalent), the rebase above will report those commits as already
  applied; drop the now-empty ones and keep our private files.
- Keep `AGENTS.md`, `ARABIC_BASE` and `.github/workflows/arabic-release.yml` out of `fix/arabic-rtl`.

---

## خلاصة بالعربي

هذا مستودعنا الخاص (fork) لـ Agora، الغرض منه إبقاء إصلاحات العربية/RTL شغّالة وبناء نسختنا الموقّعة
arm64، لأن صاحب المستودع الأصلي قد لا يقبل الدمج.

- فرع `arabic` هو فرعنا الدائم، منه تُبنى كل الإصدارات (`assembleFdroidRelease`).
- فرع `fix/arabic-rtl` هو نفس الإصلاحات بصيغة نظيفة مرفوعة كـ PR #227 للأصل.
- قاعدة العمل: اتجاه النص يُستخرج من المحتوى (`rememberContentTextDirection`) لا من لغة الواجهة،
  والمحتوى التقني (كود، مسارات، JSON، مفاتيح) يبقى LTR، ولا نغيّر عروض الصفوف.
- التحديث الدوري: `git fetch upstream master` ثم `git rebase upstream/master` على فرع `arabic`،
  نحل التعارضات مع إبقاء منطق العربية، ثم بناء واختبار ثم دفع وتشغيل الـworkflow.
- البناء عبر GitHub Actions فقط، مفتاح التوقيع خاص بنا وخارج المستودع، والمخرج ملف APK داخل Release.
