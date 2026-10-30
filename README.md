# Hiyori

An Android manga reader with on-device AI upscaling, built for people who read long-strip webtoons
on a phone and want the pages to look sharp without waiting for a server.

Hiyori is a fork of **[mihon_img_upscale]**, which is itself a fork of **[Mihon]**, which continues
the work of the archived **[Tachiyomi]** project. The reader, library, tracking and extension
ecosystem come from Mihon; the upscaling pipeline (Vulkan GPU acceleration, Qualcomm and MediaTek
NPU backends, book page turns) started in mihon_img_upscale and was reworked here — see
[What this fork changes](#what-this-fork-changes).

> **This fork is not affiliated with Mihon or Tachiyomi.** Please do not report issues with Hiyori to
> those projects — they did not write this code and cannot support it.

---

## Features

- **Library, downloads and sources** — everything Mihon provides: local and remote sources, an
  extension ecosystem, library categories, tracking (MAL, AniList, Kitsu, MangaUpdates, Shikimori,
  Bangumi, Hikka), backups, and a paged/webtoon reader.
- **On-device image enhancement** — Real-CUGAN and Real-ESRGAN models run locally through NCNN,
  with Vulkan and NPU acceleration where the device supports it. Nothing is uploaded anywhere.
- **Prewarm** — chapters you already downloaded get enhanced in the background, so opening them
  later shows the sharp version immediately instead of upgrading pages as you scroll.
- **Reader-side indicators** — a status badge and/or page-number marker so you can tell at a glance
  whether you are looking at the enhanced image or the original.

## What this fork changes

Compared with mihon_img_upscale:

| Area | Change |
|---|---|
| Prewarm candidate selection | Prewarm no longer reuses the *download* chapter selectors (which only ever returned chapters that were **not** downloaded, so the queue was always empty). It has its own selection rules and honest menu counts. |
| Empty queue | "Nothing to prewarm" is distinguished from "the window is not downloaded yet", and the queue screen explains what prewarming does instead of showing a blank list. |
| Webtoon performance | Long-strip pages are prewarmed **segment by segment**, using the same split, encoder and cache keys as the reader, so a warmed page is byte-identical to what the reader would have produced. Pages are queued breadth-first so the next thing you read is ready first. |
| First frame | When a page is already in the enhancement cache, the webtoon reader renders the enhanced strips on the **first frame** instead of drawing the original and swapping it a moment later. Cache misses are unchanged: original first, enhance asynchronously, then swap. |
| Warmed indicator | A small rocket badge on chapter rows marks chapters whose pages are all enhanced for the *current* settings. It is driven by a per-chapter completion marker plus an in-process index, so it appears on the first frame, survives restarts, and disappears when the cache or the settings change. |
| Queue page | Sorting (chapter number / join order) and drag reordering, grouped by series, with the series block order and the chapter order kept independent. |
| Reader indicators | The badge-visibility toggle and the page-number star were merged into one four-mode setting (hidden / bottom-left badge / page number / both). |

## Requirements

- Android 8.0 (API 26) or newer; arm64 device recommended
- For enhancement: a reasonably recent SoC. Vulkan and Qualcomm NPU backends are used when available,
  MediaTek NPU where supported, CPU otherwise. Large pages on weak devices will be slow — that is a
  hardware limit, not a bug.

## Installation

Download the APK for your device from [Releases](https://github.com/YouYutiao/Hiyori/releases):

| Package | Devices |
| :--- | :--- |
| `app-arm64-v8a-release.apk` | Most modern phones and tablets; choose this for NPU processing |
| `app-armeabi-v7a-release.apk` | Older 32-bit ARM devices |
| `app-universal-release.apk` | When you are unsure which ABI you need |

## Building

```bash
git clone https://github.com/YouYutiao/Hiyori.git
cd Hiyori
./gradlew assembleDebug          # or assembleRelease / assembleNightly
```

- JDK 17+ and the Android SDK are required.
- Releases are signed with a local keystore; `keystore.properties` is not part of the repository.
- Unit tests: `./gradlew testDebugUnitTest`
- Formatting: `./gradlew spotlessApply`

## Project layout

| Path | Contents |
|---|---|
| `app/` | Application module: UI (Compose), reader, downloads, prewarm queue, enhancement integration |
| `core/`, `domain/`, `data/` | Shared modules: preferences, models, database, repository layer |
| `source-api/`, `source-local/` | Source interfaces and the local (folder/CBZ) source |
| `i18n/` | Translations (base, zh-rCN, zh-rTW, ja, ko) |
| `presentation-core/` | Design-system components shared across screens |

## Credits

This project would not exist without the work of others. All credit for the foundations goes to
their authors:

- **[Mihon]** — the reader, library, download manager, extension API and the vast majority of the
  code in this repository.
- **[Tachiyomi]** — the original project Mihon continues. Archived, but everything started there.
- **[mihon_img_upscale]** (HaoweiLi97) — the fork that brought Real-CUGAN / Real-ESRGAN enhancement,
  Vulkan acceleration, NPU backends and book page turns into this codebase, and the starting point
  for the prewarm idea reworked here.
- **Real-CUGAN / Real-ESRGAN / waifu2x** — the upscaling models and their reference implementations,
  by their respective authors.
- **NCNN** (Tencent) — the inference runtime used on device.
- Every translator, extension developer and issue reporter in the upstream projects.

If you are one of these authors and want a change to how you are credited, please open an issue.

## License

Apache License 2.0, inherited from the upstream projects. See [LICENSE](LICENSE). Redistributions
must keep the copyright notices of the upstream authors.

[mihon_img_upscale]: https://github.com/HaoweiLi97/mihon_img_upscale
[Mihon]: https://github.com/mihonapp/mihon
[Tachiyomi]: https://github.com/tachiyomiorg/tachiyomi
