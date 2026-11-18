# Reader and colorization performance handoff

Status: 2026-11-18. This is a working handoff, not a claim that the fixes have
passed review or are present in an installed build.

## Two separate delays

1. Returning to an already processed chapter can take a long time before its
   saved colored image and translated text appear. Investigation found that a
   chapter switch can evict its store and reopening the store can read and
   verify snapshots across the chapter. The precise contribution of this path
   to the reported delay has not been measured on the device.
2. Creating new colored pages is slow even when NPU inference is fast. Logs
   from five consecutive MCV2 pages on the connected device on 2026-11-17 showed
   the following approximate per-page durations:

   | Stage | Time |
   | --- | --- |
   | Grayscale preparation, screentone detection, resizing and optional smoothing together | 3.9–4.1 s |
   | NPU inference | 0.92–0.95 s |
   | Output conversion and look | 0.14–0.17 s |
   | Colored image publication | 0.21–0.27 s |
   | Complete batch page | 5.3–5.6 s |

   These are observations from one run, not guaranteed timings for other pages,
   devices, models, settings or builds. The preparation substeps were not timed
   individually, so the expensive operation is still unknown.

## Existing behavior

The MCV2 route uses a quantized NPU model. The colorize-only batch skips OCR,
translation and inpainting and can reuse an existing result after matching its
source fingerprint. Pages in that batch are processed sequentially. The reader
also has a bounded bitmap handoff for freshly colored images. Relevant entry
points are `NpuMangaColorizationEngine`, `MangaColorizationPreprocessor`,
`ChapterBatchExecutor`, and `TranslationProcessor`.

## Reader storage: approved work in progress

The agreed direction is visible-page-first restoration, preserving existing
chapter formats and protecting saved data. Independent review found serious
safety gaps in the initial implementation. Re-review confirmed the main
data-loss repairs but found two remaining store-lifecycle races documented below.
This is a development snapshot, not a verified production-safe implementation.
No performance improvement has yet been verified on a device.

The independent reviewer reported six focused test classes passing (35 tests)
before the repairs, but those tests missed the safety gaps below. After repairs,
the implementer reported 42 focused tests and `:app:compileStandardDebugKotlin`
passing. The reviewer and the integrated destination tree independently ran the
six named classes: 39 tests, no failures, errors or skips. Kotlin compilation
also succeeded on the integrated tree. These checks do not cover the lifecycle
races below, and device validation remains pending.

The review found that failed or stale lazy snapshot hydration can remove the
protection marker before the saved colored image is known, allowing cleanup to
delete it. Per-file cleanup rechecks are not atomic with reopen or publication.
It also found that cancellation during the eviction flush can lose a batch of
pending writes, and that deferred deletion accounting can race with stream
leases. Other gaps include incomplete-store retry ownership, unreadable manifest
classification and user-visible deferred deletion. Re-review confirmed the
snapshot protection, write retention, retirement accounting and manifest
classification repairs, but identified these outstanding findings:

- `StoreWriteDrainCoordinator.pendingUnregisterRequested` can race with a
  reader re-adopting a retained store. Synchronize cancellation and unregister
  with store adoption so the reader cannot receive a store that is immediately
  defuncted; add a deterministic rebind-versus-drain regression test.
- Eventual unregister is not wired for every store registration path. A drain
  can mark such a store defunct while leaving it registered, causing later
  readers to reuse a non-writable store. Centralize the callback or evict
  defunct entries on every open, and test the `openExisting` path.
- Colored files are excluded from blind orphan sweeps to prevent data loss.
  Old crash leftovers may therefore persist until reset or a known retirement;
  design safe eventual reclamation with durable reachability metadata.
- A permanently unreadable snapshot retains its protection marker and can be
  re-read on every bind or prefetch. Add bounded retry/backoff without clearing
  the marker or treating failure as success.
- Pending in-memory writes remain vulnerable to process death. Define and test
  the durable completion boundary before promising persistence across restart.

Remaining work:

1. Measure warm and cold chapter returns on the affected device, including a
   large completed chapter. Capture time to the requested page, store-open
   time, snapshot reads, directory work, image decode and memory.
2. Complete and review deletion outcomes and writer coordination. A deletion
   receipt must distinguish confirmed removals, deferred leases, unsupported
   formats and failures. Preserve the crash-safe reset behavior and provide
   honest progress and recovery when leaving the reader or restarting.
3. Harden committed snapshot verification and pending-write ownership. A failed
   or timed-out drain must retain an explicit owner; in-memory writes alone do
   not survive process death. A candidate must not replace the committed
   display snapshot, and stale off-lock reads must not overwrite newer state.
4. Protect unhydrated colored images from concurrent orphan sweeps before
   enabling lazy loading. Verify the chapter-scoped fencing around cleanup,
   reopen, publication and reset; a per-file recheck alone is not atomic.
5. Load the requested verified committed snapshot first, then a bounded nearby
   window. Avoid another observer independently hydrating the whole chapter.
   Test legacy formats, rapid switching, corrupt snapshots, concurrent cleanup
   and reset during active work.
6. Run focused tests, compile, obtain the requested independent review and
   compare device timings with the baseline. Consider a recent-store cache
   only if the measured result justifies its lifecycle complexity.

The detailed design is in the Traycer artifact `reader-storage-investigation`;
that artifact is outside the repository and is not included by a Git push. This
file carries the essential decisions for a laptop handoff.

## New colorization: proposed profiling and optimization

This work has not yet been implemented as part of the storage changes.

1. Instrument `MangaColorizationPreprocessor` separately for pixel extraction,
   grayscale conversion, `ScreentoneGate`, `AreaResampler`,
   `SelectiveBilateral`, and packing. Record dimensions, gate outcome, warm-up
   and per-stage durations for representative pages; do not log page contents.
2. Benchmark representative page sizes, screentone-heavy and plain pages on
   the same device and build configuration. Include warm runs and capture
   full page throughput, memory and thermal effects. Compare the same inputs
   before and after; avoid attributing asynchronous work to the wrong stage.
3. Optimize the measured bottleneck at the existing preprocessing seam. First
   evaluate equivalent implementations that preserve the existing gate,
   resampling and smoothing behavior; do not disable quality steps merely to
   make the timing look better.
4. Verify intermediate gray planes and final colored images against the current
   implementation across the sample set. Investigate any visible changes,
   especially screentones, ink and edges. Add focused tests for the changed
   algorithm and edge cases.
5. Re-run end-to-end device timings and document before/after distributions,
   image quality and any remaining constraints. The inference-only number is
   not the end-to-end target.

The relevant timing logs use `[ColorizeTiming]`. The engine reports
`preprocessMs`, `packMs`, `inferenceMs`, `convertMs` and `lookMs`; the
processor reports `publicationMs`; the batch reports fetch, decode, commit and
total page times. Read `docs/debug-build-workflow.md` before building or
installing a debugging APK.

## Git and laptop handoff

At the time of writing, the checked-out branch is `traycer/happy-badger`, with
base commit `bc5deb74`. The user selected `archive2`'s
`debug/model-downloads-plans` branch for the handoff. Its remote tip was
`aac0d836` when checked; it has unrelated Git history and contains model
management work absent from this worktree. A direct push would not fast-forward.
Integrate and test against that branch without erasing its existing work; do
not rewrite its remote history without the required explicit approval. No
commit or push has been made for this handoff.

The worktree is shared with other ongoing work. In addition to the reader
storage changes, it contains a modified calibration dataset, Gradle settings
that include untracked experimental probe modules, and other untracked scripts
and model probes. Do not stage everything blindly: decide which experiments
belong in the handoff, and keep `settings.gradle.kts` consistent with any probe
modules included or excluded. Do not delete or revert another agent's work.

The user authorized a development handoff with work still outstanding. Check
the intended file set and remote branch and follow the repository's Git consent
rules. Once the authorized remote branch contains the intended commit, the
laptop can fetch and check out that branch.
Untracked files, ignored model assets, local SDK configuration and device model
caches do not travel in a normal Git push; provision required dependencies
separately according to the repository's model and build documentation.
