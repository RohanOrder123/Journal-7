# VERIFICATION REPORT

Date of research: 24 Nov 2026. **Nothing in this report was executed on an Android device, and the Java file and the full workflow were NOT compiled/run by me** (no Android SDK / network build in my environment). The workflow YAML and its shell steps were only syntax-checked. Items are labelled VERIFIED (found in official/primary sources), CORROBORATED (consistent third-party evidence), or UNVERIFIED.

## 1. Why 2.0.3 fails — VERIFIED
- Upstream issue #3731 announced removing espeak-ng / piper-phonemize as a breaking change → sherpa-onnx 2.0.0. https://github.com/k2-fsa/sherpa-onnx/issues/3731
- v2.0.0 release notes: "Remove espeak-ng and piper-phonemize from sherpa-onnx (#4055)". https://github.com/k2-fsa/sherpa-onnx/releases/tag/v2.0.0
- Official FAQ "How to use espeak-ng with sherpa-onnx v2.x": in v2 a lexicon is needed; espeak-ng-data in the model package "is used only by the phonemizer; sherpa-onnx itself does not read it". https://k2-fsa.github.io/sherpa/onnx/faqs/update-v1.x-to-v2.x-espeak-ng.html
- Third-party report: Piper voices "synthesize silence under 2.x unless a lexicon.txt ships". https://github.com/AACTools/rust-tts-wrapper/issues/66

## 2. Verified dependency (1.13.8)
| Item | Value | Status |
|---|---|---|
| Release | `v1.13.8` of k2-fsa/sherpa-onnx (Release PR #3945, published Sept 2026) | VERIFIED (releases page / changelog) |
| AAR file | `sherpa-onnx-static-link-onnxruntime-1.13.8.aar` | CORROBORATED: used by several projects with the URL `https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-static-link-onnxruntime-1.13.8.aar` (e.g. github.com/crooosooo/voice-notes, github.com/nguyenvanthinh95vn-cloud/mat-that-tts-android). I could not open the official asset list myself. |
| AAR size / sha256 | — | **UNVERIFIED**. Not pinned. The workflow prints both in the run summary; pin the sha256 afterwards in `SHERPA_AAR_SHA256`. |
| Maven / JitPack | Not used. | — (no coordinate invented) |
| ONNX Runtime | 1.28.2 built in (static) | CORROBORATED |

## 3. Questions from your task
1. **Android library includes Piper/eSpeak-NG frontend?** Yes for 1.13.8. CORROBORATED: projects that ship 1.13.8 on Android state it links eSpeak-NG (GPL-3.0) via sherpa-onnx (thefalconry/talon PR #1238; PurpleTV-ReVive notes espeak-ng in the 1.13.8 TTS side), and one project recompiled 1.13.8 with `SHERPA_ONNX_ENABLE_TTS=OFF` specifically to remove eSpeak NG (Arateki/Keyflow) — meaning the stock build has it. One 1.13.8 Piper-on-Android app reports real audio after copying espeak-ng-data to internal storage (mat-that-tts-android, uses Python 1.13.8 for its test). Not tested by me.
2. **`setDataDir()` supported?** Yes in 1.x Kotlin API (`OfflineTtsVitsModelConfig.dataDir`); official docs show `dataDir = ".../espeak-ng-data"` for Piper. VERIFIED from docs; the workflow re-checks it with `javap` against the real AAR.
3. **`generate()` accepts raw text?** Yes in 1.x (`generate(text, sid, speed)`; Piper uses internal phonemizer). VERIFIED from docs/API listings; `javap` check in workflow.
4. **Constructor/config API:** `OfflineTts(AssetManager?, OfflineTtsConfig)`, `OfflineTtsConfig(model, maxNumSentences…)`, `OfflineTtsModelConfig(vits, numThreads, debug, provider)`, `OfflineTtsVitsModelConfig(model, tokens, dataDir, noiseScale, noiseScaleW, lengthScale, …)`. I checked the **master** Kotlin source (`Tts.kt`) and docs, not the v1.13.8 tag file itself. Compatibility with 1.13.8 is therefore enforced by the workflow's `javap` step, not assumed. **Partially UNVERIFIED until your build runs.**
5. **Native libraries:** `libsherpa-onnx-jni.so` loaded via `System.loadLibrary("sherpa-onnx-jni")` (VERIFIED in `Tts.kt`). Presence at `jni/<abi>/` in the AAR is checked by the workflow (**UNVERIFIED by me**; the old 2.0.3 workflow used the same path).
6. **ABIs:** 1.13.8 AAR is reported to contain arm64-v8a, armeabi-v7a, x86_64 (and x86) (CORROBORATED: mat-that-tts-android "3 architectures"; google_stt PR #2). The app ships **arm64-v8a + armeabi-v7a** via `abiFilters`. x86/x86_64 emulators are not supported by this APK.
7. **Extra eSpeak data:** only the `espeak-ng-data` directory from the voice bundles (already handled; identical in both bundles, checked by `diff`).
8. **Model compatibility:** `hi_IN-priyamvada-medium` and `en_US-amy-medium` are Piper VITS bundles from the official `tts-models` release; their tarballs already include `espeak-ng-data` (VERIFIED by your previous builds: sizes and sha256 checked). That the **Hindi** voice phonemizes correctly under 1.13.8 is **UNVERIFIED** by me; the runner test in the workflow tests exactly this.

## 4. Java API used by the plugin
`OfflineTtsVitsModelConfig`: setModel, setTokens, setDataDir, setNoiseScale, setNoiseScaleW, setLengthScale · `OfflineTtsModelConfig`: setVits, setNumThreads, setDebug, setProvider · `OfflineTtsConfig`: setModel, setMaxNumSentences · `OfflineTts`: constructor(AssetManager, config), generate(String,int,float), sampleRate(), release() · `GeneratedAudio`: getSamples(), getSampleRate().
Status: same calls as your previously compiling code; each is checked with `javap` in the workflow. Removed: `lang()`. `numSpeakers()` via reflection.

## 5. Build verification checklist (done by the workflow)
Before build: required files incl. all 9 voice-bridge/`va-*` files + plugin version marker · AAR download, size, zip integrity, optional sha256 pin · AAR jni libs for arm64-v8a/armeabi-v7a · javap API check · runner synthesis test Hindi+English (peak, duration, sample count) · plugin copied + `registerPlugin` in MainActivity · build.gradle references 1.13.8 and no 2.x · manifest permissions · both onnx, both tokens, espeak files (count, `phondata`, `hi_dict`, `en_dict`).
After build: APK exists, 100–450 MB · web files · both models + tokens · espeak file count equals source · native libs for both ABIs · `SherpaTtsPlugin` and Sherpa classes in dex · `apksigner verify` + fingerprint equals `rc-debug.keystore`.

## 6. Real synthesis testing
- **In CI (proxy):** Python `sherpa-onnx==1.13.8` on Linux synthesizes both test sentences from the exact APK model files. Skipped (warning) only if the wheel cannot be installed — whether that wheel exists is **UNVERIFIED**.
- **On the phone (the real test):** call `SherpaTts.selfTestAll()` (e.g. from the browser console of the app or your debug panel). It returns `ok:true` only if both voices give samples > 0, sampleRate > 0, duration > 0, peak > 0.01 and no phonemizer error. **Until you see `ok:true` on your device, do not consider the voices fixed.**

## 7. Remaining limitations / risks
- Sherpa 1.13.8 AAR size/sha256 and internal layout: UNVERIFIED (workflow reports them).
- If a Sherpa API method is missing, the build stops at the `javap` step with a clear message (nothing is silently skipped).
- Updating over your old APK requires the same `rc-debug.keystore` and app ID — both unchanged; the workflow compares the fingerprint.
- Licensing: eSpeak-NG is **GPL-3.0** and is part of the 1.13.8 Android library; Hindi voice priyamvada is **CC BY-NC-SA 4.0 (non-commercial)**. Fine for personal use; distributing the APK publicly needs a licence review.
- 1.x is no longer the newest line. A future 2.x path needs a per-voice `lexicon.txt` (not available for these Hindi/English Piper voices in the right convention as far as I could verify).
