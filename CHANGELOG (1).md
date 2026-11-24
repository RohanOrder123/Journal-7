# CHANGELOG — Offline Piper TTS fix (Sherpa-ONNX 2.0.3 → 1.13.8)

## What was wrong (root cause)

Your plugin sends **raw text** to Sherpa and gives Piper only `setDataDir(espeak-ng-data)`.
That design needs the **built-in eSpeak-NG / piper-phonemize frontend**, which exists in sherpa-onnx **1.x**.
Sherpa-ONNX **2.0.0 removed it** (upstream PR #4055, "Remove espeak-ng and piper-phonemize from sherpa-onnx").
In 2.x a Piper model only works if a `lexicon.txt` is supplied; otherwise Sherpa prints
`phoneme_codepoints is empty, no lexicon, and no frontend available` and `Failed to convert text to token IDs`.
Your app loaded the library, models, tokens and espeak data correctly — but the 2.0.3 library simply had no phonemizer left to use them.
Nothing was wrong with your models, assets or copy logic.

## What changed

### Workflow (`build.yml`)
| Change | Why |
|---|---|
| `SHERPA_VERSION` 2.0.3 → **1.13.8**; AAR `sherpa-onnx-static-link-onnxruntime-1.13.8.aar` from `github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/` | 1.13.8 is the newest release that still contains the phonemizer. Removes the old 2.0.3 AAR reference. |
| Removed `SHERPA_AAR_BYTES` | The exact byte size of the 1.13.8 AAR could not be verified from public sources, so a made-up number would break the build. Replaced by: size sanity check (>10 MB), `unzip -t` integrity test, sha256 printed in the run summary, and an optional `SHERPA_AAR_SHA256` pin. |
| New **AAR API check** with `javap` | Before the long Gradle build, checks that every Sherpa method the plugin calls really exists in the downloaded AAR. A mismatch fails in seconds with a clear message. |
| New **runner synthesis test** (Python `sherpa-onnx==1.13.8`) | Synthesizes the Hindi and English test sentences with the same models + espeak data that go into the APK. Fails the build if audio is empty. Skipped with a warning only if the pip wheel cannot be installed. It is a Linux proxy, not the phone. |
| Extra pre-build checks | manifest permissions, `voice-bridge-entry.js`, espeak file count, no leftover 2.x references, plugin version marker. |
| Extra post-build checks | all 9 `va-*.js` files, Sherpa Java classes in dex, APK size upper bound, `apksigner verify` and comparison of the signer fingerprint with `rc-debug.keystore`. |
| Everything else kept | Node 20, Java 17, Gradle cache, `npm ci`, tests, `cap add android`, manifest patch, icons, `cap sync`, Java-17 plugin patch, model download + checksum, MainActivity registration, ABI filters `arm64-v8a` + `armeabi-v7a`, `noCompress onnx`, signing key, APK upload. |

### Java plugin (`SherpaTtsPlugin.java`)
| Change | Why |
|---|---|
| Config/API calls **unchanged** (`setDataDir`, raw-text `generate`) | These are the correct 1.x calls; the bug was the library, not the code. |
| Removed `t.lang()`; `numSpeakers()` now by reflection (diagnostics only) | Not verified to exist in 1.13.8; reflection means it can never break compilation. |
| Engine sanity check after creation (`sampleRate() > 0`) | A silently broken engine is reported as `INIT_FAIL` instead of failing later. |
| `runTest()` shared by `selfTest` and new **`selfTestAll()`** | Tests Hindi + English with your exact sentences. `ok` requires samples > 0, sampleRate > 0, duration > 0, peak > 0.01 **and** no phonemizer error in logcat. |
| Logcat is read only from the start of each test (`-T`) | Old messages cannot be mistaken for new ones. Exact phonemizer error strings are reported as `phonemizerErrors`. |
| `speak()` NO_AUDIO message now includes the phonemizer error | You see the reason, not just "no audio". |
| `stop()` during playback no longer reported as GEN_FAIL | Releasing the AudioTrack could throw inside the player loop; that is now treated as an interruption. |
| AudioTrack state check | Clear error if the track cannot initialize. |
| `engines` is a `ConcurrentHashMap` | `status()` runs on the UI thread while loading runs on the worker thread. |
| Model re-copied if the `.onnx` size differs from the APK asset | Safe after an APK update. |
| Hindi test string written with `\u` escapes | Immune to source-file encoding problems. |

## Sherpa version
**sherpa-onnx 1.13.8** (Android AAR, static-linked ONNX Runtime).

## Dependencies
- Removed: `sherpa-onnx-static-link-onnxruntime-2.0.3.aar`
- Added: `sherpa-onnx-static-link-onnxruntime-1.13.8.aar`
- Unchanged: `kotlin-stdlib:1.8.22`, all Capacitor packages (6.2.0 — not touched).
- CI only (not shipped): Python `sherpa-onnx==1.13.8` for the runner test.

## Preserved
Capacitor 6.2.0 and all plugins, speech recognition and text-to-speech plugins, voice bridge and all `va-*.js` files, app ID/package `com.rohanchauhan.tradesjournal`, permissions, launcher icon step, signing key process, model names and folder layout, plugin name `SherpaTts` and the methods `status`, `preload`, `selfTest`, `speak`, `stop`.

## Not changed on purpose
Model names, asset paths, espeak copy marker, AudioTrack streaming design, caching and sentence splitting.
