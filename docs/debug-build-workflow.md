# Debug APK workflow

Read this guide when building or installing an APK for debugging, particularly when the target device already has the required models cached. Explicit task instructions and required quality checks still apply.

## Fast path: one ARM64 debug APK

Use this path when the target is `arm64-v8a`, the installed app has the required compatible models cached, and the test does not need bundled model assets. From the repository root in PowerShell:

```powershell
.\gradlew :app:assembleStandardDebug "-Ptarget.abi=arm64-v8a" "-Pmodels.exclude=true"
```

The APK is normally `app/build/outputs/apk/standard/debug/app-standard-debug.apk`. `target.abi` limits native libraries to that ABI and disables ABI splitting. `models.exclude` excludes the `models` asset directory, skips `fetchModels`, and disables MCV2 and QuickSRNet model-asset staging. Qualcomm runtime libraries are separate from model assets and may still be staged and verified when configured.

`models.mangaLight.hybrid.dir`, if configured, still causes the MangaLight staging task to inspect and copy its files even with model assets excluded. Do not assume this flag skips every model-related task. Inspect the build configuration and produced APK when behavior differs from expectations.

These flags select APK contents; they do not download or provision models on the device. The app must already have each model needed by the scenario, in the correct package's cache and compatible with that engine. The NPU colorizer and upscaler check their caches when assets are absent. MangaLight accepts its existing cache only when each artifact matches its expected size and SHA-256. Other model loaders have their own cache and validation behavior; check the relevant loader when in doubt. A fresh install, cleared app data, changed package ID, or incompatible model can invalidate this assumption.

Before an authorized install, confirm the target device, package ID, signing compatibility, and that replacing the running app will not interrupt a test or recording. An update using `adb install -r` generally retains app data; an uninstall or clearing app data removes app-private caches. Do not uninstall or clear data as a routine debugging step.

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" -s '<device-serial>' install -r 'app\build\outputs\apk\standard\debug\app-standard-debug.apk'
```

Use the configured Android SDK's `adb` if it is elsewhere, and replace the placeholder with the intended device serial. Installing an APK can stop the running app; restart it and reattach any PID-scoped logging as needed.

## Verification and exceptions

- Confirm that the build succeeded and inspect the actual APK when model exclusion or ABI matters; stale outputs and changing Gradle properties can mislead. If the APK size is unexpectedly large, inspect its ZIP entries and build output before deciding how to repair the packaging issue. Do not delete outputs by a fixed size threshold or suppress build checks.
- If a required model is missing, unavailable, or fails validation, identify its loader and provisioning requirements. Use a suitable model-included build or an explicitly supported provisioning method; do not assume every loader shares the same cache directory or validation rules.
- Use the relevant flavor and variant requested by the task. The `dev` flavor has a reduced resource configuration, so it is not automatically equivalent to `standard`. Use a model-included build when testing model packaging, first-run setup, or release behavior.
- Run the tests, lint, and build checks appropriate to the change. A fast debug APK does not replace required verification. Do not drop Qualcomm libraries or other runtime dependencies merely to reduce APK size without validating every affected backend and device.
- For device diagnostics, preserve any ongoing test and recorder. Follow the active session's logging instructions, and identify the correct app package and PID before collecting or filtering logs.
