---
title: "Translation Module Refactor — Audit Ledger (Phase 1/2 working record)"
kind: spec
comments: none
---

# Translation Module Audit Ledger

Working record of the Phase-1 audit for `app/src/main/java/eu/kanade/translation/` (278 files, \~61k lines) plus `domain/.../translation/` and reader-UI wiring. Maintained by the orchestrator; updated as findings are cross-verified. Line counts vary slightly between agents (blank-line counting); treat ±5% as noise.

## Verification rules (binding for all agents)

1. Every DEAD claim AND every LIVE claim needs an exact-symbol grep with caller @line across `app/src/main` + `app/src/test` + `app/src/androidTest`.
2. Filename greps are forbidden as evidence — extension functions, companion extensions, and renamed classes defeat them (BubbleMaskRleEncoding case).
3. Zone-local path inference is not caller evidence (AiChunkAdaptiveRetry case: file calls governor types, but nothing calls its entry point).
4. Orchestrator spot-verifies every claim before it enters the refactoring plan.

## Audit fleet

| Agent | Zone | Model | Status |
| --- | --- | --- | --- |
| audit-domain-ui-crosscheck (b2d0da77) | domain + reader UI + whole-module sweep | GLM-5.3-Flash (opencode, max) | DELIVERED |
| audit-pipeline-core (653db65e) | coordinator + processing + model + manager root | GLM-5.3-Flash (opencode, max) | DELIVERED |
| audit-engines-ml (67e31312) | inpainting + coloring + translator + rendering | GLM-5.3-Flash (opencode, max) | DELIVERED (2 claims overruled, see contradictions) |
| audit-engines-vision-runtime (d2f142ce) | vision + runtime + upscaling | Gemini-3.8-Flash (antigravity, high) | DELIVERED (3 parts) |
| audit-persistence-diagnostics (087c3b19) | persistence + diagnostics | Gemini-3.8-Flash (antigravity, high) | PENDING (nudged for interim) |

## SECTION A — Confirmed deletable (production-zero-reference, orchestrator-verified where marked ✓)

### A1. Never-wired scaffolding (coherent, tested, unshipped) — product decision: DELETE (lean) vs WIRE

- \[✓] `processing/planning/PageWorkPlanner.kt` (508) + `PageWorkPlan.kt` (116) — zero prod callers; only ref is a comment at `TranslationStageContracts.kt:23`. Live resume predicates in RTC + BatchPageOperation carry production behavior.
- \[✓] `engines/translator/retry/AiChunkAdaptiveRetry.kt` (1,139) — entry symbol `translateAiChunkWithAdaptiveRetry` called ONLY from `AiChunkAdaptiveRetryTest` + `ContextualRequestBuilderTest`. All exports (`AiChunkOutcome`, `AiTranslationRetryPolicy`, `FrozenAiEnvelope`, `ResponseAnalysis`) self-referential in main. Production retry = `TranslationRetry.kt:135 withTranslationRetry` + governor. Deleting requires repointing ContextualRequestBuilderTest.
- \[✓] `engines/translator/retry/AiTranslationRetryPlanner.kt` (59) + 2 test files — zero prod callers (both agents agree).
- \[✓] `engines/translator/contextual/OcrCorpusManifest.kt` (233) — in main, symbol appears only in its own file; GlobalEnvelopePlanner does NOT call it (engines-ml's "sole caller" claim overruled by grep).
- D3 SETTLED (pipeline FINAL): DELETE the never-wired planning layer. Planner requires BatchPlannerInput data the live scans never load; semantics differ by design (dependency-ordering, retry-cooldown, fingerprint provenance vs flat OCR-barrier); wire-in ≈ +200-300L glue on the most incident-dense path. Revisit only if per-stage retry provenance becomes a product requirement.

### A1c. Second never-wired stack: chapter-envelope/chunk planning (engines-ml Phase-2, corroborates pipeline)

- \[✓-engines-ml] `StreamingChunkPlanner.kt` (272) — UNWIRED: only constructed at TranslationContextChunkPlanner.plan:71, which has no production caller.
- `TranslationContextChunkPlanner` — SPLIT verdict: `estimateTokens` IS LIVE (InputAccountingContract:40-109, OpenAiCompatibleTranslator:137, GoogleTranslator:94/113, DeepLTranslator:77); planning surface UNWIRED (\~150 of 261L): `plan` (:63), `withRollingContext` (:84), `updateRollingContext` (:122, sole caller = dead BatchContextFrontier:80), `constraintsFor` (callers = dead StreamingChunkPlanner + dead ChapterContextService:121). RESOLVED DISCREPANCY: pipeline's Phase-2 item-4 passing clause called plan/StreamingChunkPlanner "live" — WRONG per orchestrator exact-symbol grep (all live refs = estimateTokens; all other referencing files ledger-dead). engines-ml verdict stands; live chunk is built inline by AiTranslator.translate (LEGACY).
- Module total: THREE independent never-wired systems — planning layer (A1, \~1,550L), AiChunkAdaptiveRetry (A1, 1,139L), envelope/chunk stack (A1b+A1c, \~1,060L). LIVE AI truth: processor → AiTranslator.translate(LEGACY) → provider translateContextual(Structured) → RequestBuilder → HTTP → Parser → applyBatchToChunk; retry = TranslationRetry:135 + governor. BatchTranslationProtocol IS LIVE (ContextualResponseParser:264) — keep.
- New small dup: GeminiTranslator.translate (:73-86) duplicates AiTranslator.translate (:55-67) LEGACY-chunk body (\~12L).

### A2. Dead coloring chain — triple-verified

- \[✓-pipeline] `engines/translator/contextual/GlobalEnvelopePlanner.kt` (406) — UNWIRED: exact-symbol grep = self-refs + KDoc in dead OcrCorpusManifest.kt:79 only; tests only (GlobalEnvelopePlannerTest + GoldenTest). Live AI path confirmed as: TranslationProcessor.translateBatch:430-454 → AiTranslator.translateContextualStructured:69-70 → provider overrides → ContextualRequestBuilder (ZERO refs to planner/EnvelopePlan, read in full :27-131).
- Corollary deletion unit (verify each at implementation): `model/EnvelopePlan.kt` (87); `StageFingerprints.envelopePlanInputFingerprint/.envelopePlanContentFingerprint` (:521/:550); `ChapterArtifactManifest.envelopePlan` field (:40 — always null in production stores; read sites needing co-edit: CompletedDownloadRekeyCoordinator:226 **+ ArtifactRetention:223 (blind-sweep addition)** + ChapterStageMerges:196 (dies with A6)); `ChapterArtifactLayout` envelopes/ dir (:52/:103-106); `ChapterArtifactEngine.envelopePlanSidecarName` (:300). BATCH_V1 request/response protocol (RequestBuilder/ResponseParser) IS LIVE — do not touch. \[Blind-sweep double-check COMPLETE: GlobalEnvelopePlanner UNWIRED, StreamingChunkPlanner UNWIRED, EnvelopePlan COMPILED-BUT-DORMANT — all confirmed independently.]
- D3 SETTLED (pipeline FINAL): DELETE the never-wired planning layer. Planner requires BatchPlannerInput data the live scans never load; semantics differ by design (dependency-ordering, retry-cooldown, fingerprint provenance vs flat OCR-barrier); wire-in ≈ +200-300L glue on the most incident-dense path. Revisit only if per-stage retry provenance becomes a product requirement.
- \[✓] `engines/coloring/OnnxMangaColorizationEngine.kt` (74) — zero refs in main AND test AND androidTest.
- \[✓] `engines/coloring/MangaColorizationOnnxSession.kt` (152) — sole constructor caller is the dead engine (engines-ml verified chain).
- \[✓] `engines/coloring/ColorizationBackendRouter.kt` (132) + its test — self-reference only (✓ orchestrator grep).
- \[✓] `engines/coloring/UnprovisionedColorizationEngine.kt` (22) + test — self-reference only; NOTE: `UnprovisionedUpscalingEngine` is ALIVE (`UpscalingModelCatalog.kt:26`) — same-suffix ≠ same fate.
- \[✓] `engines/runtime/onnx/OnnxModelStore.ensureMangaColorization()` (:277) / `…Int8` (:288) — CONFIRMED transitively dead (sole callers MangaColorizationOnnxSession:96-97). OnnxModelStore CLASS ITSELF IS ALIVE (FfdnetDenoiserEngine:147, RoiPageRecognitionEngine:70, FumetoTranslateEngine:40, OCR session factories) — delete the two METHODS only (\~20L).
- FINAL DELETION UNIT (domain-ui Phase-2): files = OnnxMangaColorizationEngine.kt (65) + MangaColorizationOnnxSession.kt (135, INCLUDING internal enum MangaColorizationModelRequirement declared at :12 — dies with file, domain-ui extension) + ColorizationBackendRouter.kt (109) + UnprovisionedColorizationEngine.kt (15); methods = OnnxModelStore.ensureMangaColorization + ensureMangaColorizationInt8; tests = ColorizationBackendRouterTest + UnprovisionedColorizationEngineTest (nothing else to repoint — no tests touch the engine/session). EXCLUDE MangaColorizationModelStore (ALIVE via both engines — C4 rehome only, no delete).

### A3. Dead processing/pipeline files — pipeline-core zone, orchestrator-verified subset

- \[✓] `processing/AttemptAuthority.kt` (277) — all 4 decls self-referential (✓ grep).
- \[✓] `processing/PageStoreWriter.kt` (206) — class self-referential (✓ grep); `toPrecondition` ext (197-206) ALREADY DUPLICATED at ChapterTranslationStore.kt:187 → consolidate onto the store, delete with class (persistence Part 2 #10). Decoy names: prod uses `ChapterPageMutations.updatePageFromCurrentSnapshot` + `MemoryGovernance.handleCriticalTranslationOom`.
- \[✓] `context/ChapterContextService.kt` (169) + `context/BatchContextFrontier.kt` (110) — service constructed only in `RollingHistoryContextContractTest:341`; rolling-context logic duplicated live in `TranslationContextChunkPlanner.updateRollingContext:122`.
- \[✓] `processing/execution/PreparedPageBoundary.kt` (22), `processing/PostOcrStageSemantics.kt` (24), `processing/DeferredPagePublications.kt` (55), `processing/batch/BatchPersistenceRejectedException.kt` (7), `batch/BatchAdmissionRouting` inside `BatchAdmissionProbe.kt` (33) — test-only or zero-ref (✓ grep for several).
- CORRECTION (domain-ui Phase-2): `BatchPass1Outcome.kt` (26) is NOT standalone-deletable — imported by LIVE files (TranslationBatchProgressTracker:20,161 + BatchProgressReconciler:9,36,177); dead only TRANSITIVELY (tracker.pause(outcome) has no prod caller — prod `.pause(` hits are batchCoordinator.pause, a different method). Bind into ONE deletion unit: {BatchPass1Outcome.kt + BatchProgressReconciler + tracker.pause surface + PagePhase/AiPageProgress events} per the A3 partial row. NOTE (persistence Q3): StoreStatusProjector and BatchProgressReconciler.reconcileFlaggedCompleted do NOT overlap — persistence Q3 REFUTES pipeline's "real overlap" claim: both delegate page-counting to the already-shared ChapterPageReconciler.reconcile (StoreStatusProjector:127, BatchProgressReconciler:70); disjoint concerns (passive durable-status query vs batch-loop controller), no shared fields, no race. KEEP BOTH; reconciler deletion unit removes ONLY the test-only reconcile()/reconcilePaused() paths (:32-75, :174-259).
- \[✓] `coordinator/TranslationStoreResolver.kt` (19) — self-reference only (✓ grep).
- \[✗ CORRECTED 2026-11-12\] `util/TaskExtensions.kt` (16) — "zero refs" was WRONG: `Task<T>.await()` ext has 3 live callers — TextRecognizer.kt:29, MLKitTranslator.kt:50/:70. FILE KEPT; excluded from Stage 1.
- Partial (final grep owed at implementation): tracker `mark*` surface + `PagePhase`/`AiPageProgress` events + `PagePhaseStatusView` (\~180L), reconciler `reconcile()`/`reconcPaused()` (\~125L), write-only Progress plumbing (\~25L), `PageDecode.computeSourceFingerprint` + `batchExpectedFingerprints` (59L), `MemoryGovernance` preflight gates + `handleCriticalTranslationOom`, `DevicePagePermitGate` promotion machinery (\~60L), `ReaderSubmission.Promoted`, `TM.nativeStall` facade, `TranslationProgressProjection.observePageView` (471-476) + `observeActiveDisplayStore` wiring.

### A4. Dead domain + vision

- \[✓] `domain/translation/KeystoreApiKeyManager.kt` (148) + `domain/translation/validation/PreferenceValidator.kt` (55) — zero refs anywhere (domain-ui agent; wrong-layer anyway).
- \[✓] `engines/vision/ocr/TranslationBlockSorter.kt` (92) — zero main callers, 2 tests; superseded by `ReadingOrderSorter` (two agents agree).
- \[✓] `engines/runtime/onnx/CheckNnapi.java` + `CheckXnnpack.java` (1 line each) — zero refs (✓ grep).
- \[✓] `engines/vision/segmentation/BubbleMaskRleEncoding.kt` (19) — companion-extension `BubbleMaskRle.encode` called ONLY from `BubbleSegmentationDecoderTest` (✓ grep). Production encodes RLE inline at `BubbleSegmentationDecoder.kt:200`. Resolution: move encode() to test scope (it is the test fixture builder) — NOT a plain delete.

### A5. Persistence-zone dead code (persistence agent interim, grep-evidenced)

- \[✓-interim] `persistence/ChapterStageMerges.kt` (416) + 3 forwarding methods on `ChapterTranslationStore` (:517/:522/:527) — property `stageMerges` referenced only inside the store; `store.mergeOcr/mergeTranslation/mergeRender` have ZERO production callers (tests: mergeOcr ×2 in ChapterTranslationStorePhase3Test:147,195). Production writes via `store.updatePageGuarded` (ChapterPageMutations). DELETE + repoint 2 test calls. (NOTE: agent's prose cites PageStoreWriter as production — slip; PageStoreWriter itself is A3-dead. Verdict unaffected.)
- \[✓-interim] `persistence/CleanedImagePublication.kt` → SPLIT: `CleanedImagePublisher` class DEAD (0 prod callers; sole non-test mention is a KDoc link at ChapterDisplayPublication.kt:30); `CleanedImageIdentity` + `CleanedImageIdentitySidecar` ARE live (imported by processing/CleanedPublication, CleanedImageLifecycleController, ChapterArtifactEngine) → rehome metadata types (own file or processing/CleanedPublication). AUTHORITATIVE verified-write: processing/CleanedPublication (TranslationProcessor.kt:117). Delete CleanedImagePublisherTest.

### A6. Micro-deletions inside live files (verify at implementation)

`NeuralInpaintingCoordinator.freeNativeSessions` (prod-dead, −4; twins `reclaimPooledMemory`/`forceReleaseNativeBuffers` ARE live via `RoiPageRecognitionEngine:1373/1406`); `BubbleMaskBuilder.featherAlpha` test-only alias (−6, repoint 2 test lines); `OpenCvInpaintEngine` wrappers `inpaintTelea/inpaintPixels/inpaintBitmap` (−45, repoint 3 BubbleMaskBuilderTest sites; prod uses `inpaintPixelsWithBackend` at Coordinator:1066); `AotOutputGuard.isSuspiciousUniformFill` test-only.

**Running total A1–A6: ≈ 4,900–5,400 main-source lines, plus paired test files.**

## SECTION B — Structural splits (god files, seams verified by reading)

| File | Lines | Split proposal |
| --- | --- | --- |
| `NeuralInpaintingCoordinator.kt` | 2,033 | 6-way: AotSessionProvisioner / InpaintRegionRouter / BubbleInpaintStage (erodeBinaryMask → BubbleMaskBuilder) / AotFixedInpaintExecutor / DynamicInpaintPipeline (legacy, duplicates fixed-path tensor logic L1605-1877 — optional unify −150L) / facade. 66 test files cover. |
| `RoiPageRecognitionEngine.kt` | 1,457 | 3-way: PageRecognitionPipeline / VisionEngineFactory / SpatialGroupingStage. |
| `BatchTranslationCoordinator.kt` | 1,138 | queue persistence \~250 / worker loop \~450 / lifecycle \~250 + SHARED join protocol with RTC (dups verified: sessionSwitchJobs 613-616 vs 331-335; bounded join 596-611 vs 337-364; isChapterBatchActive ×3; TRANSLATING→QUEUE ×3). |
| `ReaderTranslationCoordinator.kt` | 1,118 | extract StageSignalBoard (1091-1118), outcome registry (757-802), viewport auto-scheduler (540-625). |
| `TextLayoutPlanner.kt` | 1,332 | extract \~300L model types → PageLayoutContract; BREAK forwarding cycle CollisionRelaxation↔Planner→FontFittingAlgorithms (−35L). TextLayoutCoordinator is GENUINE (identity/generation/stale-delivery) — do not merge. |
| `TranslationProcessor.kt` | 971 | stage primitives vs single-page orchestrators (processSinglePage 739-925, colorizeOriginalPage 189-293). |
| `ProviderRequestGovernor.kt` | 945 | 3 seams: failure vocabulary → ProviderFailureModel.kt / batch sub-limit gates → own file / core stays. Cohesive, NOT a zoo (corrects brief suspicion). |
| `TranslationUiTruth.kt` | 999 | split status-line chain 649-999 → BatchStatusLineProjection.kt; i18n debt CORRECTED by blind-sweep J3-2: **126 hardcoded English user-visible strings** (not 19 — that count was only the `fallback = "` literals; distribution: pill vocabulary :135-578, page-stage words :264-269, per-page outcomes :394-578, batch hero/status chain :673-935 incl. 8 BatchHeroPhase literals :874-887); resources wired for only 2 kinds (`ReaderNavigationDock:287`). i18n migration remains deferred debt — not this plan. |
| `TranslationManager.kt` | \~764 | extract ColorizationEngineOwner (\~90L); merge `TranslationManagerCoordinators.kt` (121, dead processor branch 58-63) into `TranslationManagerInitialization.kt` (239). |
| `MangaOcrEngine.kt` | 885 | extract LiteRT vs ONNX runner halves. |
| `OnnxRuntimeProvider.kt` | 759 | extract session builders. |
| `PaddleOcrV6SmallEngine.kt` / `PaddleOcrV6DetEngine.kt` / `VerticalLineOcr.kt` | 776/680/677 | extract LiteRT runners / session lifecycle / image-math vs plan. VerticalLineOcr is misnamed (helper, not engine). |
| `ReaderTranslationController.kt` | 1,025 | settings-writer (\~210L one-line setters) vs session orchestration vs lifecycle; collapse ReaderViewModel L146-251 30-flow nested combine into \~5 named intermediate flows. |
| `QnnDiagnostics.kt` | 978 | RESOLVED (critical review): option (a) — move to debug source set — is IMPOSSIBLE: Gradle forbids src/main referencing src/debug symbols and App.kt (main) registers the receiver (:181-189, action const :317); moving the receiver too would require refactoring the Application entry point (out of scope). FINAL: condense in place, keeping main visibility; runOnce() + receiver keep working; 978L → \~200-300L. ✓ the adb-broadcast-only finding stands. |
| `TranslationTrace.kt` (1,420) + `TranslationPipelineDiagnostics.kt` (1,205) | 2,625 | CONDENSE the heavyweight translation_trace_v1 pair (100% logcat-write-only); keep logcat as sink; merge 3 trivial wrappers (AutoSmoothnessTelemetry 89, NativeVisionTelemetry 105, DeviceStageNormalization 20) into `TelemetryTrace` (101, the light spine, 41 main refs). Batch{Download,Translation}Diagnostics + ReaderEntryTrace KEEP (real structured consumers; domain-ui: ReaderEntryTrace has EXTERNAL consumers DownloadManager:224 / ReaderNavigationController / WebtoonViewer:257 / DownloadPageLoader:49 — must NOT fold into this merge). ORCHESTRATOR GATE RESOLVED (persistence Q4): HMAC-SHA256 (TranslationTrace:342-368 TranslationIdentityKeys) = privacy pseudonymization — random in-memory 32-byte key, 16-hex tokens for chapter titles/paths (c\<hex>), page files (p\<hex>), schedule IDs (s\<hex>) to keep user content out of plaintext logcat; exceptions strictly whitelisted (TranslationPipelineDiagnostics:56-65). translation_trace_v1 has NO external consumers (2 prod files + 1 test; 0 scripts parse it). FINAL TARGET: keep TranslationIdentityKeys.token() + exception whitelist VERBATIM; condense the 17 trace data classes + ThreadContextElement reflection + overlap-accumulator math + multi-lane timers → \~500-line structured logcat emitter. BLAST RADIUS (domain-ui): AutoSmoothnessTelemetry + NativeVisionTelemetry have 23 external consumer sites in ui\reader — keep the two object names as thin facades over the merged spine (LOW risk, RECOMMENDED) or accept a 23-site mechanical repoint. engines-ml conditions for NativeVisionTelemetry: already a pure formatting façade over TelemetryTrace.log (:25-:96); repoint 4 call sites (PaddlePageOcrCoordinator:321, ReaderTranslationController:298, ObservationController:519, Actions:376); NativeVisionTelemetryTest's 6 assertions need byte-identical `domain=… event=…` line format; preserve TARGET_MS=2000. CRITICAL-REVIEW ADDITIONS: (1) `App.kt:197` (main) sets `TranslationPipelineDiagnostics.detailedTracingEnabled` — the property must survive the condense or the main Application class breaks; (2) `scripts/analyze_latency_budget.py:27,69` parses `event=budget_summary` (totalNativeMs/det/ocr/inpaint) and `event=arrival_wait_recorded` (emitted AutoSmoothnessTelemetry:46) — those formats must survive byte-for-byte (script-facing observability constraint, alongside the NativeVisionTelemetry test formats). |
| `ChapterBulkMutator.kt` | 728 | SPLIT: extract rekey logic (5 multi-page transactions). |

## SECTION C — Duplication to merge (verified)

1. **Vision dual-runner boilerplate \~250L ×3**: OnnxPageTextDetector / OnnxPanelDetector / OnnxBubbleSegmenter share LiteRT+ONNX session creation, GPU→CPU fallback, buffer pooling, memory reclaim → extract `VisionModelRunner` (−450L, MED).
2. **QNN staged cache/promotion — FINAL SHAPE (vision + engines-ml Phase-2)**: QnnVisionContextCache has exactly 2 prod callers (OnnxPanelDetector.kt:84, OnnxBubbleSegmenter.kt:67); NeuralInpaintingCoordinator:251-298 consumes QnnContextCacheManager DIRECTLY with the same 4-call protocol (getValidCachedModel/invalidate/prepareStaging/commitStaging) but inpaint-specific steps (AotModelContract.validate, ModelRoutingEngine.markSupported, QnnSessionMemoryPrecheck, contextCacheFile=null) and different options maps that are cache-key relevant. Duplication is SUPERFICIAL-TO-PARTIAL, not byte-copy: generalize a Plan-style object on QnnContextCacheManager parameterized by (options map, onServed validator); 3 consumers benefit. Δ −60-90L (not −120), MED risk. ORDERING: dedup-THEN-split Coordinator (AotSessionProvisioner comes out \~310L not \~400L); merge QnnVisionContextCache (106, thin adapter) into QnnContextCacheManager (161).
3. **QNN precheck copy-paste**: `dispatchPrecheck` + `REQUIRED_LIBRARIES` byte-identical in NpuMangaColorizationEngine:199-209,380-385 ↔ MangaLightHybridColorizationEngine:257-268,250-255 (−30L, LOW).
4. **Model asset provisioning — SETTLED (vision + engines-ml Phase-2)**: STEP 1 (this plan): rehome `copyAssetWithDigest` + `sha256Of` from MangaColorizationModelStore (:11,:27) → `engines/runtime/ModelDeployment` (which already owns readStamp/writeStamp/computeStamp/cachedFileMatchesStamp). Consumers after: LiteRTQuickSRNetUpscalingEngine:10 (severs upscaling→coloring import), NpuMangaColorizationEngine:225, MangaLightHybridColorizationEngine:130. MangaColorizationModelStore keeps whatever remains or dies if empty. STEP 2 (optional, larger): engines-ml's `ModelArtifactProvisioner` absorbing OnnxModelStore.copyIfNeeded + LiteRT inpaint asset ladder + Npu extractModel — provisioning-phase (disk state, idempotent) deliberately NOT fused with vision's `VisionModelRunner` (runtime-phase session lifecycle) — two efforts, one shared foundation (util.Sha256 + ModelDeployment). Vision boilerplate spot-check CONFIRMED mechanically extractable (identical close/reclaim/fallback bodies OnnxPageTextDetector:250-271 vs OnnxPanelDetector:272-293; identical DirectBufferPool(1*3*640*640*4, max 2); identical runOnnxFallbackAfterLiteRtFailure).
5. **Verified-write recipe**: processing/CleanedPublication:66-90 ↔ persistence/CleanedImagePublication:260 — sha256+sidecar JPEG write ×2. Persistence agent's verdict pending on authoritative owner.
6. **Memory facts ×6** (orchestrator count): ActivityManager.MemoryInfo in TMB ×3 + EMB ×2 + RoiPageRecognitionEngine:417; identical heap-snapshot logic TMB.snapshot:80-92 ↔ EMB.heapSnapshot:34-46 → one owner (−90L, LOW-MED).
7. **OCR dedup pair**: OcrBlockDeduper (59, geometric/TranslationBlock) + OcrBlockDeduplication (241, Detection/RecognizedBlock) — unify into one dedup module (−60L, LOW).
8. **Device-profile duplication**: PaddleOcrDeviceProfile duplicates DeviceCapability fields → absorb (−140L with policy merges, LOW).
9. **Pixel primitives**: histogramMedian+avg4 (InpaintPixelOps ↔ PushPullGradient), maskValue ×3 (AotOutputGuard private), reflect() (ScreentoneGate ↔ SelectiveBilateral) (−40L, LOW).
10. **Translator small merges**: DeepLApi→DeepLTranslator (−25); TranslationBlockFilters→ContextualResponseParser (−15); normalizeCandidateTarget → reuse TranslationOutputSemantics.normalizedForComparison (byte-equivalent, −4); InputAccountingContract parameterization (−60); OcrArtifactSanitizer rename-move (single implementation, consumers in 3 zones).
11. **LineArtMerger → CpuChromaCombiner** (sole prod caller; pure sRGB↔CIELAB; AGSL has shader twins) (−95L, LOW).
12. **ML Kit cascade**: TextRecognizer (35) + MlKitOcrPreprocessor (81) → into MlKitRoiOcrEngine (27) (−50L, LOW).
13. **Forwarding cycles/bounces**: CollisionRelaxation private fns bounce through TextLayoutPlanner to FontFittingAlgorithms (−35L); PaddleOcrV6BatchCtcDecoder (44) → PaddleCtcDecoder; PaddleOcrDetProviderSelection (22) → SessionFactory; PaddleOcrBatchActivationPolicy (75) → DevicePolicy.
14. *Chapter family over-fragmentation (persistence Part 1)*\*: AMENDED by pipeline + domain-ui Phase-2. Merges CONFIRMED: ChapterArtifactRekey (228) → ChapterArtifactEngine; ChapterCandidateTransactions (683) → ChapterArtifactEngine; ArtifactRetention (230) → ChapterArtifactEngine; ChapterDisplayPublication (288) → ChapterTranslationStore — 100% SAFE (persistence Q5: it borrows store.mutex via `private val mutex get() = store.mutex`, NO independent lock, zero eviction-timing change, eliminates 12 trampoline forwarders at store:529-566) (domain-ui: zero UI-side reach for ALL consolidated symbols — shell sweep of ui\reader + presentation + data\translation = ZERO hits); ChapterStoreTestSeams (28) → store; WriteDiagnostics (10) → ChapterPageMutations (3 call sites: Bridge:144 + PageMutations:156,175 — Bridge call site stays in Bridge file post-dispute; 10L inline still net-positive, verify at implementation). **DISPUTED & REVERSED: ChapterStorePublicationBridge (436) does NOT merge into ChapterPageMutations** — pipeline caller inventory: 4 live clients (ChapterPageMutations :48/:143/:212/:333/:354/:398, StoreWriteDrainCoordinator:163 deliberately bypasses PageMutations, ChapterBulkMutator :87-:505 all under store.mutex, ChapterDisplayPublication :254/:280); merge-safe but merge-WRONG (−300L claim was a caller miscount; real Δ≈0; would create \~950-1000L god file with new coupling). Bridge stays its own file. Core keepers: ChapterTranslationStore (facade), ChapterPageMutations (CAS spine), ChapterDocumentIo (atomic I/O), ArtifactContracts (51/41 refs), TranslationStageContracts (808 refs), ChapterArtifactEngine (388 refs, the hub). BRIDGE DISPUTE CLOSED: persistence agent ACCEPTED the reversal in its Phase-2 Part 1 (verified the 4-client inventory itself, incl. BulkMutator:287-485 rebase/ensureArtifactStore sites). ChapterStoreTestSeams verdict REFINED (persistence Q1): 3 members — afterRekeyManifestPublicationBeforeLiveSwap (crash-boundary seam: prod caller BulkMutator:284, ZERO test callers — hook awaiting a test that never came), publishedPageCopies (2 test files: PublishedPageStoreStateTest:97-110, ReadOnlyPageViewTest:39-62 → repoint to store.publishedPageCopies), countPublishedPageCopy (prod caller store:665). Merge as internal members: −28L, zero production risk.

## SECTION D — Architectural defects / decisions

- **D1. SETTLED — KEEP-SEPARATE-BUT-DOCUMENT (vision Phase-2 rationale, evidence-based)**: the "split-brain" framing was WRONG — the bypass is intentional per-engine scoped resolution. Root cause: `HardwareDiscoveryEngine.activeRoute` is a single GLOBAL process-wide latch (`@Volatile`, latched once at cold startup by vision+inpainting models); PaddleOCR needs per-request provider selection (CPU/QNN_GPU/QNN_HTP user-facing staged benchmarks). PaddleOcrSessionFactory ALREADY calls `HardwareDiscoveryEngine.resolvePaddleOcrRoute(requested)` (HardwareDiscoveryEngine.kt:106) — isolated probe WITHOUT touching activeRoute or the global circuit breaker; probe failures cached per-provider locally (probeFailureCache). Unifying would cause CROSS-MODEL CIRCUIT-BREAKER CONTAMINATION (one PaddleOCR QNN_HTP failure would latch CPU_XNNPACK globally, permanently disabling QNN for vision models); ModelRoutingEngine's per-model-ID demotion also mismatches PaddleOCR's synchronous in-memory CPU retry + honest provider telemetry. PLAN ACTION: document PaddleOcrSessionFactory as an intentional per-engine scoped provider resolver (KDoc), do NOT unify.
- **D2. PageTranslationKey collision bug (CONFIRMED, ✓ known)**: `PageTranslationKey.kt:4-5` URL-tail keys → `OnlineColorizePageStreamResolver.kt:132` distinctBy drops colliding pages → page-1 colorization shown on all. Fix shape agreed (hash full URL or index+URL, drop distinctBy, versioned keys for persisted artifacts, reader-side key stamping in lockstep). BEHAVIOR-CHANGING — separate approval gate. BLAST RADIUS EXTENDED (blind-sweep J3-3): `onlinePageTranslationKey` also consumed by `data/download/DownloadTranslationHandoff.kt:12` AND `ui/reader/ReaderPageWorkResolver.kt:8` — fix must stamp reader AND download paths in lockstep, or download-persisted artifacts re-introduce the collision.
- **D3. Planner product decision**: DELETE never-wired scaffolding (A1) vs wire PageWorkPlanner into resume scans. Orchestrator lean: DELETE (git preserves history; live ad-hoc predicates are production truth; wiring unwired 508L planner late = speculative architecture). Puts A1 at \~−2,300L.
- **D4. Domain layering**: BitmapPool (android.graphics in domain!) + DirectBufferPool → move to app `processing/memory/` (both used by 10+ engines — verified); domain could become JVM-pure (hypothesis, compile-time enforcement).
- **D5. Misplacements (Frankenstein map)**: executeColorizeOnly inside "BatchPageOperation" (also \~40L recipe dup with processor.colorizeOriginalPage 217-255 ↔ 222-256 — consolidate); cleaned-image lease manager inside "TranslationStreamRegistry" (158-323 → persistence lifecycle); Throwable.isFatal() in MangaColorizationImageProcessing consumed module-wide → shared util; TranslationUiProjection.protectsChapterFromDeletion consumed by TRC:691-699 → HOME SETTLED (domain-ui Phase-2): coordinator package — extract alongside twin policy protectedChapterIds (TRC:701-713) into coordinator\ChapterDeletionProtection.kt or inline in TRC; persistence (ChapterDataResetController) EXECUTES deletion, must not own policy; tests repointing: TranslationUiProjectionTest:35-43 (3 assertions), TranslationManagerAutoDeleteProtectionTest:20 (compile-check only); MaskGeometryClustering.kt names SharedMaskSession class; MaskGeometry (642L geometry engine) in model/ DTOs (hypothesis: move near consumers); `eu.kanade.translation.presentation` package name collides conceptually with app's presentation — naming debt, boundary itself REAL (5 consumers of TranslationUiTruth ✓; if BatchStatusLineProjection is extracted per B-row, KEEP the TranslationUiTruth facade symbol or repoint 5 files — decide at implementation, facade recommended).
- **D6. Naming — SETTLED by C4 STEP 1**: MangaColorizationModelStore's shared helpers rehome to `engines/runtime/ModelDeployment` (vision Phase-2 final architecture); upscaler→coloring import severed.
- **D7. Confirmed-but-bounded performance characteristic (persistence Phase-2 Q2)**: NO monolithic chapter-document serialization exists (page snapshots are content-addressed individual files — ChapterCandidateTransactions:69/:240). The real per-page I/O: manifest.json is re-serialized + atomically rewritten on EVERY page transition when lazy persistence is OFF (batch mode: ChapterCandidateTransactions:93/:280/:444/:520 → ChapterArtifactEngine:458), i.e. O(chapter²) bytes over a full batch run in the worst case; reader mode coalesces via StoreWriteDrainCoordinator:156-176 (250ms debounce, PERSIST_DEBOUNCE_MS). PERSISTENCE AGENT VERDICT: bounded, no architectural rewrite warranted. ORCHESTRATOR ACCEPTANCE: document as known characteristic; revisit only if a measured batch-mode I/O problem appears. This closes the fleet's JSON-hot-path question with CONFIRMED evidence — speculative performance item NOT added to the plan.

## SECTION E — Explicitly ALIVE (do not touch — verified false positives)

Fumeto package (all 5 files, selectable via StandardTranslatorKind:47); TranslationUiTruth + projection boundary; TextLayoutCoordinator; OcrBlockDeduper/Deduplication pair semantics (complementary, merging only as C7); MemoryPressurePolicy/DevicePagePermitGate/BatchAdmissionProbe (trim vs permit vs probe — complementary); ReadingOrderSorter; TranslationSafetyPrimitives; VerticalLineOcr (alive, 2 main callers); ChapterPages canonical (one documented mmap divergence); ChunkPlanner pair — CORRECTED GRANULARITY (blind-sweep J3-1): delegation SHAPE holds, but the pair's `plan()` entry is UNWIRED; ALIVE is true only at token-helper granularity (`estimateTokens` + companions). `updateRollingContext` is dormant-on-dormant (sole caller = dead BatchContextFrontier). See A1c.; text-filter quintet semantics DISJOINT (engines-ml verification); CleanedImageIdentity/Sidecar types (live in 3 zones — rehoming only).

## SECTION F — Consolidated opportunity estimate (pending persistence zone)

- Dead/never-wired deletions: ≈ −4,400 to −4,900 main lines
- Merge/dedupe (C): ≈ −1,200
- God-file splits: net ≈ 0 lines, major navigability gain
- Bug fix D2: +\~20 net, separate gate
- Paired test deletions/edits ride along with each item.
- Expected end state (reconciled with critical review): \~61k → \~52-53k lines net ≈ −8,000 ± 500 (of which \~2,100 is the Stage-4 diagnostics condense; deferring the condense gives ≈ −5,900); \~30-40 fewer files; zero behavior change except D2 (gated).

## SECTION G — Open items

1. persistence-diagnostics: COMPLETE (Parts 1+2). Zone est. −3,300 LOC. All suspects verdicted. New items: toPrecondition consolidation (A3), Chapter\* 7-merge set (C14), diagnostics condense with privacy gate (B). Prose slip noted twice ("PageStoreWriter is production" — false, A3-dead); verdicts unaffected.
2. ALL FIVE ZONES + BLIND-SWEEP DELIVERED — Phase 1+2 fully closed (blind-sweep: 11/11 sampled ledger claims HOLD; all 10 reachability verdicts confirmed; new findings J3-1..J3-7 recorded). Contradiction record: 3 resolved against zone-path-inference (AiChunkAdaptiveRetry, OcrCorpusManifest, ChapterStageMerges-by-name), 1 filename-grep false negative (BubbleMaskRleEncoding), 1 target-home disagreement resolved by locality principle (CleanedPublication → persistence), 1 clause-level liveness error (pipeline on chunk-planner, orchestrator grep). Blind-sweep method gaps: ALIVE claims need caller-chain granularity; string counts need full-pattern sweeps; cross-zone consumer enumeration must include data/download direction.
3. Vision agent delivered in 3 messages; transcript-based per-file table received; its "erroneous import" claim overruled (see C4).
4. Phase-2 cross-review COMPLETE — all 6 reviews reconciled (pipeline, engines-ml, domain-ui, vision, persistence Q1-Q5, blind-sweep pending-or-absorbed). Key resolutions: D1 KEEP-SEPARATE (circuit-breaker contamination), D3 DELETE, Bridge merge reversed+accepted, EnvelopePlan + chunk-planner stacks unwired (orchestrator grep), StoreStatusProjector/Reconciler overlap REFUTED (shared ChapterPageReconciler core), HMAC = privacy pseudonymization (KEEP, condense around it), QnnDiagnostics plan-breaker defused, QnnDedup −60-90L dedup-then-split.
5. Phase-3 COMPLETE: plan artifact drafted (translation-refactor/refactoring-plan/) → independent critical review delivered VERDICT: BLOCKED with 4 MUST-FIX + 6 SHOULD-FIX — ALL RESOLVED in plan + this ledger: ArtifactRetention:223 co-edit restored to Stage 2; ChapterBulkMutator split restored to Stage 4; RTC's own seams restored; QnnDiagnostics move-to-debug ELIMINATED (Gradle: src/main cannot reference src/debug) → condense in place; App.kt:197 detailedTracingEnabled + scripts/analyze_latency_budget.py event formats added as binding constraints; Stage 5 steps 2+3 declared atomic; compile loop extended to unit+androidTest Kotlin tasks; estimates made transparent (net ≈ −8,000 ± 500 incl. condense; −5,900 excl.). Plan is implementation-ready pending user approval.
6. Phase-3 plan artifact (HTML, for user) + this ledger (for agents) → critical review agent (Gemini 3.8 Flash high, fresh) before any implementation.
7. User gates: D2 behavior change; D3 delete-vs-wire; D1 routing unification scope; all git actions per AGENTS.md §8.

## SECTION H — Stage-6 implementation reconciliation (2026-11-13)

Scope: ACTUAL outcomes of the implementation wave covered by this agent's reports plus orchestrator gate results. Deferred work is NOT marked complete. Line counts stated only where measured this wave. "Gate passed" = orchestrator-reported compile+test gate result over the worktree (this agent runs no builds/git/adb per mandate).

### Completed (gate-passed unless noted)

1. **BatchPageOperation → ChapterBatchExecutor** (D5 / plan :159 companion rename): class+file renamed, 5 caller/test repoints (imports/type names only); test class name `BatchPageOperationTest` deliberately kept (cosmetic, not required). In tree for every subsequently reported passed gate.
2. **ProviderRequestGovernor split** (B :96): `ProviderFailureModel.kt` + `BatchRequestSublimitGate.kt` extracted; governor 945→810 (measured). Core + **priority machinery + SharedBatchRequestSublimitGate (F2 item) preserved in place — deliberate**, not an omission. Gate passed.
3. **TranslationUiTruth split** (B :97): status-line chain → `BatchStatusLineProjection.kt`; **TranslationUiTruth facade kept** per plan note. Gate passed.
4. **ReaderViewModel combine collapse** (Stage 5 step 1): 30-flow nested combine → 5 named intermediate flows + flat terminal combine (7 named data classes; `Quad` deleted). Engine-flow flattening REVERTED on coordinator order — strict operator topology preserved. Gate passed.
5. **Memory facts** (C6 / plan :168): TMB.snapshot + Snapshot deleted; **EngineMemoryBudget is the sole heap-snapshot owner** (+ `SystemMemoryFacts` helper); MemoryGovernance + 2 test files repointed (imports/type names only); PageDecode untouched (field access only). Measured net ≈ **−25L** — the plan's −90L estimate overstated the dedup surface (correction recorded). **ACTIVITY-MANAGER SITES — plan "×6 → 1" AMENDED TO 2 DELIBERATE SITES**: `EngineMemoryBudget.systemMemoryFacts()` (single consolidated read for nnapi/headroom/log consumers) + RoiVisionEngineFactory's ORIGINAL engine-context lookup, restored and kept — Injekt can fail where the passed context succeeds, so failure behavior is not equivalent (orchestrator ruling; conservative 0L tier preserved in both paths). Gate passed.
6. **ChapterBulkMutator split** (B :105): rekey logic moved verbatim → `ChapterPageRekeyMutator.kt` (two-phase plan/prepare/publish transaction, planning machinery, placeholder checks, stale checks, `afterRekeyManifestPublicationBeforeLiveSwap` seam intact); ChapterBulkMutator 728→331 (measured), keeps the other 4 transactions + a signature-identical `rekeyPages` delegate; zero caller/test repoints; `pageRekeyMutex` lifetime preserved (one per store; store constructs the mutator once). Gate passed (reader/manager/rekey).
7. **TranslationPreferences split** (Stage 5 step 4, facade-first): `AiEngineCredentials` (19 members) / `PipelinePrefs` (17) / `ReaderUiPrefs` (10); facade keeps ALL 46 members + companion (encode/decode helpers, limits, defaultOcrModel) + all 7 enums verbatim; **42 preference keys verified exactly-one-definition**; Preference-creation semantics unchanged (per-invocation store lookups, no caching). ONE GATE FAILURE recorded and fixed: PipelinePrefs:63 unresolved `defaultOcrModel` (unqualified companion call lost its enclosing-class scope in extraction) → qualified `TranslationPreferences.defaultOcrModel(...)`; defaults unchanged; subsequent gate (admission/preferences/ONNX) passed.
8. **TranslationManager merge** (B :98 / plan :164): Initialization + Coordinators merged into `TranslationManagerCoordinators.kt` (`TranslationManagerInitialization.kt` deleted); all 7 factory extensions moved verbatim (same package → zero TM.kt/test changes). **Dead processor branch deleted on full caller evidence** (not constructor assumption): exactly one construction site repo-wide which always passes a non-null override, zero test constructors, TM.processor non-null by type (TM:136), property order guarantees initialization before the runtime factory (TM:136 → TM:214). Orphaned `streamRegistry` param removed; `context` KEPT (feeds BatchTranslationCoordinator); pre-existing unused imports swept (`uy.kohesive.injekt.api.get` ×2 files, `ReaderSessionIntent`). **STATUS 2026-11-14: SUPERSEDED — the reader/manager/rekey combined gate PASSED (see Section I).**

### Deferred (explicitly — do not treat as complete)

9. **TranslationProcessor split + executeColorizeOnly→colorizeOriginalPage consolidation** (B-row / plan :160 / D5): DEFERRED by orchestrator ruling — no lease or durable-marker changes authorized. Seam evidence: single-page orchestrators are member-mocked in ~5 test files (extension conversion requires mockkStatic rewrites, not import repoints); stage primitives are bound to private state (`withNativeLane`, lazy `cleanedPublication`, `stableSeed`, `recycleIfNeeded`). Consolidation evidence: the ~40L dup is behavioral divergence, not a textual accident — the colorize-only pass is deliberately LEASE-FREE, writes `batch:colorize-running/-commit/-decode-failed` markers (test-pinned; stranded-page sweep keys on them), owns run-level fingerprint reuse; executeBatch already delegates textless pages to `colorizeOriginalPage(origin=BATCH)`. `TranslationProcessor.kt` remains whole (audited 971L, unchanged by this wave).
10. **Diagnostics condense** (QnnDiagnostics in place; TranslationTrace+PipelineDiagnostics → ~500L emitter): unit-level outcome NOT evidenced in this reconciliation — owned by another agent. Binding constraints remain binding: App.kt:197 `detailedTracingEnabled`, scripts/analyze_latency_budget.py event formats, `TranslationIdentityKeys.token()` privacy scrubber verbatim, exception whitelist, NativeVisionTelemetry line format.
11. **D2 page-key collision fix**: evidence-only phase complete — positional-prefix and C′ hash schemes both REJECTED (wrong-record-reuse / contamination risk); algorithm + persisted-key migration decision deferred to a separate post-wave gate. Safe-failure doctrine on the thread: unproven identity → re-run, never claim another page's record.

### Corrected owner class names (plan vs reality)

12. **"ColorizationEngineOwner"** (plan :164, B :98) → actual extraction name: **`TranslationManagerColorization`** (pre-existing file; TM holds wiring + delegating `colorizationEngine` property only). Absence of the plan's literal class name alone is not evidence of completion — verified by content on coordinator correction.
13. **"BatchPageOperation"** → `ChapterBatchExecutor` everywhere except the test class name `BatchPageOperationTest` (kept deliberately).
14. New homes this wave: `ChapterPageRekeyMutator`, `ProviderFailureModel`, `BatchRequestSublimitGate`, `BatchStatusLineProjection`, `AiEngineCredentials`, `PipelinePrefs`, `ReaderUiPrefs`, `ChapterBatchExecutor`; memory-facts owner `EngineMemoryBudget` (+ `SystemMemoryFacts`) — matches plan :105 "one memory-facts owner (EngineMemoryBudget)".

### Evidence class

15. Other agents' zones (vision splits, pools move D4, asset-IO D6, QNN dedup, persistence merges): orchestrator gates reported PASSED over the tree containing their work; unit-level outcomes are NOT recorded here (not evidenced to this agent). Plan-estimate corrections recorded only where measurement disagreed (memory facts −90L → ≈ −25L net).

## SECTION I — Wave-2 reconciliation (2026-11-14)

Written by the doc-only reconciler agent: no code edited, no builds/git writes run. Sources: seven agent transcripts (implementation 28deed55; audits d634c811 persistence, 6e4ac797 processing, 81038fed coordinators; reviews 84929ab3, 9d9b80a2, e84fcb80, plus batch-commit-boundary reviewer 88a6849e) + this agent's own read-only git measurement of the worktree at HEAD `0752b750`. "Gate passed" = coordinator-reported compile/test gate over the worktree.

### (a) What shipped, measured

**UPDATE (later 2026-11-14):** the wave was checkpointed as local commit `7afa1e5b` "Reconcile translation ownership and prune dead surfaces" — 126 files, +9,194/−12,732 — including the persistence consolidation, all splits, the preference split and reader seam (the review-flagged extractions were committed by owner decision, objections on record), the CleanedImagePublisher removal, and today's three coordinator actions. Gate over that tree (coordinator-reported): compile green, **286 suites / 1,784 tests / 0 failures / 1 skipped benchmark**. Pending entries dropped 131 → 16. The follow-up structure move then landed as local commit `74c9d2fc` "Rehome batch admission and extract deletion protection" with the full unit gate (**287 suites / 1,784 tests / 0 failures / 1 skipped benchmark**); only these two doc files remained, and they are committed now. Nothing pushed. The measurements below are the pre-checkpoint state and remain the wave's measured actuals.

- **Worktree state (measured 2026-11-14):** 131 pending entries — 77 modified + 26 deleted tracked files, 28 new untracked files. All UNCOMMITTED; nothing pushed. Tracked diff vs `0752b750`: +3,015/−13,128 (production 79 files net ≈ −8,401; tests 24 files net ≈ −1,712). Untracked additions: +6,155L (tests +161L of that). Overall net ≈ −3,958L once new seam files are counted.
- **Implementation agent's report vs its own baseline (reported, not re-verified per unit):** ≈ −10,799L / −34 files (production ≈ −5,317 net over 15 files; tests ≈ −5,533 over 19 files). The difference vs the worktree numbers above is the coordinator's follow-up work plus new seam files, both outside the agent's report scope.
- **Measured structural actuals (audited size → today):** NeuralInpaintingCoordinator 2,033→430 (5 stage files extracted); RoiPageRecognitionEngine 1,457→310 (pipeline/factory/grouping); MangaOcrEngine 885→241 (+2 LiteRT/ONNX runner files); OnnxRuntimeProvider 759→399 (+options/registration/sessions — split FLAGGED, see (b)); BatchTranslationCoordinator 1,138→676 (+BatchChapterPipeline 331, committed in `0475c56f`); ReaderTranslationCoordinator 1,118→893; TranslationUiTruth 999→549 (+BatchStatusLineProjection 412); TranslationProcessor 971→930 (kept whole, split deferred); ChapterBulkMutator 728→317 (rekey → ChapterPageRekeyMutator 402; H's 331 was measured pre-displayPublication repoint); ChapterTranslationStore 824 (DisplayPublication + TestSeams absorbed); ChapterArtifactEngine 1,505 (CandidateTransactions/Rekey/Retention absorbed); TranslationStageContracts 180→103 (stage-patch vocabulary deleted); ChapterDataResetController →189 (14 pass-through wrappers inlined); StoreWriteDrainCoordinator →119; ProviderRequestGovernor 810 (H) →749 today; TranslationManager 629 + TranslationManagerCoordinators 327.
- **Committed locally during the wave (user-authorized per-verified-step policy; all ancestors of HEAD):** `07d89aec` write-drain task queue, `2717e02b` image-cleanup delegation, `74a6db46` batch admission routing, `4658f659` page fingerprint helpers, `87b74b18` device permit promotion, `0475c56f` batch chapter execution + TerminalExits tests, `af588a57` store resolver, `54a252bc` chapter context service, `fedeb756` provider failure/sublimit split, `8d957896` certified accounting bounds (InputAccounting consolidation), `5cd9f52f` Paddle CTC consolidation, `182148fb` bubble-mask fixture to tests, `a441db28` block sorter, `b50ff565` ONNX probes, `8044be1e` domain prototypes, `126b2368` CPU chroma conversion, `47b431c7` DeepL routing, `4b3ef31f` watermark filtering, `a643b718` adaptive AI retry, `74a75986` OCR corpus manifest, `0f4e2411` global envelope planner, `bfe284e2` colorization failure notice, `11a2024d` Manga Light init logging, `0a4cf7ad` shared fatal-error classification, `915a74e5` border reflection share, `685413a5` bitmap ownership helpers, `83a9b03f` processing boundary helpers, `f08a7031` colorization model deployment, `b35cadc1` reader page execution + stage signals, `9e56aed4` page store writer, `0a73fe1d` test discovery guard, `0752b750` Paddle OCR batch dispatch.
- **Coordinator actions today (2026-11-14, in worktree, compile green per coordinator; unit suite PENDING):** (1) dead stage-patch vocabulary deleted from TranslationStageContracts (5 patch data classes + sealed StagePatch + StagePatchResult; self-referential only once its 3 prerequisites — ChapterStageMerges deletion, store merge forwarders, Phase3Test pruning — were already in the worktree; PageWriteOrigin/toArtifactOrigin/PageStageLease/LeaseAcquisition preserved); (2) ChapterDataResetController 14 private pass-through wrappers removed, injected lambdas inlined; (3) ORIGINAL colorization preset implemented as the model authors' recipe — MangaColorizationImageProcessing.applyLook() dispatches ORIGINAL to a direct bilinear resize of the model RGB to source size, VIBRANT to the existing ink-aware combine; both engines (NPU MCV2, Manga Light hybrid) call it.

### (b) Audit dispositions (wave-2 audits)

**Persistence audit (d634c811):**
- ACCEPTED (in worktree, uncommitted): ChapterDisplayPublication + ChapterStoreTestSeams absorption into the store (12 trampolines die; `afterRekeyManifestPublicationBeforeLiveSwap` @Volatile seam and publish-copy counter preserved verbatim); ChapterStageMerges + store merge forwarders + Phase3Test pruning (prerequisites for the stage-patch deletion).
- ACCEPTED (committed): ChapterTranslationAccessController forwarding removal (`2717e02b`); StoreWriteDrainCoordinator dead lazy-task queue + unused `afterFlush` callback removal (`07d89aec`, 0 external callers verified at HEAD and worktree).
- REJECTED as standalone-at-HEAD: stage-patch deletion alone would not compile at HEAD (prerequisites above); valid only bundled with them — executed that way in the worktree.
- DEFERRED: CleanedImagePublisher removal — file also carries the moved-in `persistence.CleanedPublication` class; deletion requires hunk-level sequencing in the persistence cluster (coordinator ruling after the auditor's zero-risk framing was challenged).

**Processing audit (6e4ac797):**
- ACCEPTED (committed): dead `progressFlows`/`getProgressFlow` deletion (zero consumers; per-chapter leak), BatchAdmissionRouting removal (`74a6db46`), DevicePagePermitGate promotion machinery (`87b74b18`), PageDecode dead fingerprint helpers (`4658f659`), context trio (`54a252bc`), store resolver (`af588a57`), InputAccounting consolidation after the requested API review (`8d957896`).
- REJECTED (candidate shape): retry deletions as standalone commits — both entry points consumed at HEAD by M test files (entangled with the contextual unit); `[P]` progress unit as 2 files — corrected to 4 (TranslationManagerCoordinators:309 + ChapterTranslationStatusOffMainThreadTest:199 consumers).
- RETRACTED by the auditor itself: `@Volatile` on the colorization engine var (verified `isAvailable()` REBUILDS a closed engine — the annotation is irrelevant; real requirement is ownership-based deferred close); the terminal-snapshot-at-transition toast seam (tracker publishes snapshots asynchronously after the status write; two paths set ERROR before the abort event is queued); "HTP driver exhaustion = unified root cause" (downgraded to unproven physical risk; the code-proven rebuild race is independently sufficient).
- DEFERRED: InputAccounting safety review (resolved — commit landed); BatchAdmission* rehome to coordinator (queued, not started); colorizeOnly queue restore regression — FOUND by batch-commit reviewer 88a6849e (BatchQueuePersistence did not re-persist colorizeOnly ids), FIXED via `persist(queue)` + regression tests (consecutive restores, concurrent queue addition); the covering combined gate PASSED.

**Coordinator audit (81038fed):**
- ACCEPTED: SessionSwitchJoinProtocol + TranslationStageSignalBoard verified sound; progressFlows deletion corroborated; PaddlePageOcrBatchDispatcher extraction independently verified faithful (pure 0/120 relocation, cancellation/crop-ownership intact).
- FLAGGED, NOT committed — owner decision since recorded: ReaderTranslationSettingsWriter (circular coupling to the controller, 20 one-line forwarders, 0 external callers → reject/revise); TranslationPreferences split (behavior parity verified, but flagged shallow forwarding +161L with 0 external callers); OnnxRuntimeProvider 3-file extension split (recommend REJECT — receiver extensions own zero state); ROI/inpainting extension-split pattern (preserve session-owning runners, revisit forwarding splits); TranslationManagerColorization commit deferred (coupled to coloring classes in motion). **DISPOSITION (same-day): ALL committed in `7afa1e5b` by owner decision; the review objections stand on record against the committed shape.**
- CORRECTED by coordinator: its ticket-04 report mischaracterized `b35cadc1` as in-flight cancellation on scroll — HEAD's queue-only viewport prune was deliberately restored; also the min commit set grew from 5 to 10 files (practical 13) after the reviewer found 2 compile blockers + 1 size error in the coordinator's own closure map (admitReaderSession absent at HEAD; observeActiveDisplayStore arg dropped).

### (c) Cross-auditor conference resolutions

- **Projection boundary:** "execution emits events, coordinator projects" confirmed stable — processing/batch/progress owns TranslationBatchEvent/Tracker/Registry; TranslationProgressProjection consumes read-only. No churn fix applied; the reconstructDurableTerminalSnapshot duplication question stays observational.
- **Join/defunct semantics:** bounded joins (2s batch, 3s session switch) never assume native-thread termination; continuation safety rests on `markDefunct` + generation fencing + store eviction + NativeRunQuarantine's real-exit rule. Invariants recorded: defunct stores are never reused (fresh open via ActiveChapterStoreRegistry); `closeAndFlush` before unregister; two-phase rekey fenced by `isChapterRekeying`; batch's unguarded snapshot-merge writes under `PageWriteOrigin.BATCH` are a deliberate contract, not drift vs the reader's lease-guarded path.
- **Lease pinning during native hang:** MANUAL colorize can hold PAGE_BUSY until the native call's real exit (lease finally-blocks run only after quarantine returns); accepted as a known consequence of the real-exit rule — no lease-repair change made.
- **Colorization engine lifetime:** per-generation state machine (ACTIVE→DRAINING→CLOSING→CLOSED, lease tokens, close strictly outside the lock, eager fresh replacement at shutdown) converged between the processing and runtime auditors after the coordinator rejected two earlier sketches (close-with-outstanding-borrows; CLOSED declared before close completes). DESIGNED, NOT implemented — would land in TranslationManagerColorization + 4 borrow sites.

### (d) Independent review outcomes

- **Store absorption review (84929ab3):** zero semantic divergence; 12 trampolines die; mutex order, snapshot publishing, retention queueing, crash hook identical. Approved as a standalone atomic unit. Warnings recorded: store grows 613→824L (crosses the AGENTS.md §4 threshold — any future restructure must extract a genuine seam, not a wrapper); ChapterArtifactEngine had ballooned to ~1,450L — do not bundle the store unit into it.
- **Paddle dispatcher review (9d9b80a2):** behavior-preserving, no defects; pre-existing test gaps noted (non-cancellation Throwable branch, window-full path, multi-bucket finishPage ordering); cosmetic — PaddlePageOcrPolicy/Mode still live in the coordinator file.
- **Reader/manager review (e84fcb80):** settings-writer extraction byte-equivalent, but the `isAutoTranslateEnabled` consolidation carries a now-load-bearing undocumented write-before-collect invariant at `onReaderAutoToggled`, a naming collision with the coordinator wiring's same-named auto-only predicate, and 4 dead controller imports; test gaps on the writer's real logic. A non-wrapper boundary (constructor injection) exists if the extraction is retained.
- **Regression found and fixed:** colorizeOnly queue restore (see (b), processing audit) — the one wave regression surfaced by review; fix gated.
- **Batch commit-closure challenge (88a6849e + 81038fed):** the coordinator's proposed batch commit closure was audited symbol-by-symbol, 3 defects found and owned, corrected set = 10 minimum / 13 practical files.

### (e) Device-probe evidence and limits

- Passed device probes (earlier waves): MCV2 W8A8, FFDNet, QuickSRNet direct engine, LiteRT inpainting, Paddle OCR. These are DEVICE evidence — no JVM suite substitutes for them.
- NOT resolved: Manga Light on-device init stall (init logging added in `11a2024d` did not fix the stall); QNN strict HTP/GPU diagnostics still fail.
- Colorization silent-failure investigation (user-reported both-models-fail): root cause class = code-proven swap/rebuild race closing out with HTP context exhaustion as unproven amplifier; two mitigations committed (`bfe284e2` notice, `11a2024d` logging); ownership fix designed, not implemented.
- Limits: every gate this wave is coordinator-reported compile+unit-tests over the shared worktree. The pre-checkpoint "suite pending" state was superseded the same day by the `7afa1e5b` gate run (286 suites / 1,784 tests / 0 failures / 1 skipped benchmark — coordinator-reported, not run by the reconciler).

### (f) Remaining backlog

| Item | Status |
| --- | --- |
| D2 page-key collision fix | Deferred — both candidate schemes rejected; needs evidence-class migration design; behavior-changing gate. Not started. |
| TranslationProcessor split + executeColorizeOnly consolidation | Deferred — no safe seam (test-pinned `batch:colorize-*` markers; deliberate lease-free divergence). |
| Diagnostics condense (QnnDiagnostics; TranslationTrace pair → ~500L emitter) | Deferred AND risk-flagged — review found the proposed reduction would remove live behavior; only a stale-comment cleanup landed. Redesign required before any attempt. Binding constraints unchanged. |
| CleanedImagePublisher removal | ~~Deferred~~ **DONE** — landed in checkpoint `7afa1e5b` (zero references remain in main+test; the moved-in CleanedPublication/sidecar hunks sequenced in the same commit). |
| BatchAdmission* rehome to coordinator | **DONE** — landed in `74c9d2fc`: BatchAdmissionProbe.kt + test moved to coordinator/; consumer repoints in MangaScreen, MangaScreenModel, MangaTranslationCoordinator, TRC and TranslationManager. |
| TranslationUiProjection.protectsChapterFromDeletion extraction | **DONE** — landed in `74c9d2fc`: coordinator/ChapterDeletionProtection.kt + test extracted; predicate and protected-id union keep identical inputs and semantics; TRC + TranslationUiProjection repointed. |
| Engine-ownership lease implementation | Designed (per-generation state machine), awaiting owner sign-off; lands in TranslationManagerColorization. |
| Flagged extractions kept in tree (TranslationPreferences split, ReaderTranslationSettingsWriter, OnnxRuntimeProvider 3-file split, ROI/inpainting extension splits) | RESOLVED by owner decision — committed in `7afa1e5b`; review objections recorded in (b) and stand against the committed shape. |
| i18n migration (126 hardcoded strings) | Separate effort, not this plan. |
| Reader/persistence/vision cleanup child agents | CLOSED — their work landed in `7afa1e5b`; the agents are idle with no reports outstanding. |
| AGENTS.md conformance — god files (9 ≥700L, see (h)) + 3-line comment cap (114 inline runs, 803 block comments) | OPEN BACKLOG — module recorded as NOT compliant on these two rules; no code edited under this doc-only mandate. |

### (g) Evidence class

- **Transcript-evidenced:** all audit/review verdicts, retractions, the colorizeOnly regression + fix, gate outcomes including failures and repairs (wave-1 gate, persistence-unresolved, coloring syntax, VisionModelRunner visibility, SmallEngine brace, PageWriteOrigin unresolved, preferences defaultOcrModel, ONNX imports — each failed gate was repaired and re-run to green; reader/manager/rekey, admission/preferences/ONNX, pool-move, and queue-fix gates all PASSED).
- **Measured by this agent (git/filesystem, 2026-11-14):** pending-entry counts, numstat totals, all file line counts cited above, branch/HEAD state, commit ancestry.
- **Coordinator-reported, not independently verified:** compile-green status of today's three actions; all gate green runs themselves (this agent runs no builds).
- **Reported only (not re-verified per unit):** the implementation agent's −10,799L/−34-file composition.
- **Not evidenced:** any on-device behavior from this wave; the unit-suite outcome for the pre-checkpoint tree (superseded by the `7afa1e5b` gate run).

### (h) AGENTS.md conformance scorecard (2026-11-14, at `7afa1e5b` — coordinator-measured, doc-only)

Coordinator measurement over the translation module at `7afa1e5b` (266 files, 60,625 raw / 47,207 code lines). Recorded verbatim as evidence; NOT independently re-verified by the reconciler.

- **Size buckets** (<200 / 200-500 / 500-700 / 700-1000 / 1000+ code lines): 183 / 65 / 9 / 7 / 2. **18 files ≥500 code lines; 9 ≥700 — god-file candidates: ChapterArtifactEngine 1,258 · TranslationTrace 1,013 · TranslationPipelineDiagnostics 953 · ReaderTranslationCoordinator 853 · TranslationProcessor 852 · QnnDiagnostics 840 · TextLayoutPlanner 780 · TranslationRequestCoordinator 775 · LiteRTMangaInpaintingEngine 757.**
- **Naming:** only two generic `*Manager` names remain — TranslationManager.kt (505 code lines, public facade) and QnnContextCacheManager.kt; 105/266 files carry an explicit behavioral role suffix (rest are mostly model/contract types). Largely compliant.
- **Comments — NOT compliant:** 114 `//` runs and 803 block comments (KDoc included) exceed the 3-line cap. Worst inline runs: ChapterStorePublicationBridge 15, BatchStatusLineProjection 13, EngineLane 13, ChapterDataResetController 13, ChapterPageMutations 12 and 11.
- **Hygiene — compliant:** 0 TODO/FIXME; 7 "commented-out code" regex hits, all prose false positives; stale-symbol sweep clean.
- **Seams (observation):** 13 injected-provider accessors of the form `private val x get() = xProvider()` remain in 5 files (ReaderTranslationCoordinator, TranslationProgressProjection, TranslationRequestCoordinator, DurableChapterStatusResolver, CleanedImageLifecycleController).
- **VERDICT (binding for the backlog):** structure and hygiene largely compliant; **NOT compliant on the ≥700-line god-file threshold and the 3-line comment cap**. The 9 god files and the two failing rules are explicit open backlog; the module must NOT be marked AGENTS.md-compliant.

## SECTION J — Wave-3 reconciliation (landed units, 2026-11-14)

Coordinator-written from the delivered structure audit, child reports, and parent-run gates. Child agents edited source; the parent ran Gradle/git, reviewed every diff, and removed two new over-cap comment headers before commit. Nothing pushed.

### (a) Audit delivered

- Report: `scratch/post-cleanup-structure-audit.md` — 211 lines, 15 ranked findings, 274 module files / 61,144 lines at its baseline.
- Audit exclusions honored: diagnostics condensation (`TranslationTrace`, `TranslationPipelineDiagnostics`, `QnnDiagnostics`), `TranslationProcessor` split / `colorizeOnly`, D2 page-key collision migration, native admission/reconciler pairs.
- Two audit claims corrected before or during implementation:
  1. **Finding 2 grouping was wrong.** `PageTranslationView.kt`'s "284-line serializer" is actually the shared value-equality/hashCode/toString layer; the serializer is 10 lines. The approved split is interfaces / published models + serializer / value semantics, not the audit's two-file serializer split.
  2. **Finding 10's zero-caller claim is not test-safe.** `TranslationUiTruth.ordinalSuffixOf` is called by tests; deleting it requires rewiring those tests, so F10 is deferred rather than treated as zero-risk.
- The range correction for Finding 2 was also captured: the class KDoc and `@Serializable(with = PublishedPageTranslationSerializer::class)` annotation at original lines 106–112 had to move with the model; dropping them would have silently changed journal serialization.

### (b) Finding dispositions

| # | Finding | Disposition |
|---|---|---|
| 1 | Extract `ChapterCandidateTransactions` | **DONE** `fe6fa840` — engine 1,258 → 283 code lines; new file 606 code. |
| 2 | Split `PageTranslationView` | **DONE** `963fa551` — corrected three-file shape: interfaces 94 code; `PublishedPageTranslation` 340; `PageTranslationValueSemantics` 252. |
| 3 | OpenAI common-logic dedup | **NOT STARTED** — still deferred. |
| 4 | Delete `ProductionTextMeasurer.kt` | **DONE** `963fa551` — 0 callers; 35 lines deleted. |
| 5 | Merge `TranslationQueueStatusProjection` | **DONE** `963fa551` — `translationQueueStatusFlow` now internal top-level in `BatchTranslationCoordinator.kt`; 23-line file deleted. |
| 6 | Clean `TextLayoutPlanner` forwarders | **DONE** `963fa551` — `isCJK`, `graphemeClusters`, `verticalGlyph`, `verticalOrientation` deleted; `TextLineBreaker` calls `FontFittingAlgorithms.isCJK` directly. |
| 7 | Extract mask regions | **DONE** `963fa551` — planner 989 → 838 raw / ~664 code; `TextLayoutMaskRegions` 152 raw / ~119 code. `centeredExtentOverlapsObstacle` deliberately stayed in the planner: it depends on the planner's private `MIN_GAP_PX` and is a text-extent collision check, not mask partitioning. |
| 8 | Move `toUiPauseReason` | **NOT STARTED** — still deferred. |
| 9 | Move stores to `persistence` | **NOT STARTED** — still deferred. |
| 10 | Clean `TranslationUiTruth` forwarders | **DEFERRED** — tests call `ordinalSuffixOf`; any deletion must rewire tests. |
| 11 | Repackage `AiModelFetcher` | **NOT STARTED** — still deferred. |
| 12 | Extract image GC from store | **NOT STARTED** — needs a design for private mutable state, not a mechanical move. |
| 13 | Delete store/manager dead methods | **DONE** `963fa551` — `ChapterTranslationStore.authorizeCleanedImageDeletion`, both `releaseAllPageLeases` methods, and `TranslationManager.translateChapters` deleted; 0 callers verified. |
| 14 | Extract LiteRT asset lifecycle | **DONE** `963fa551` — engine 961 → 812 raw / 742 code; `LiteRtInpaintingAssetManager` 160 raw / ~146 code. This intermediate step was followed by the patch-clusterer extraction in `a73aebe7`, which brought the engine below 700. |
| 15 | Consolidate `BatchPhase` | **DONE** `963fa551` — enum moved into `TranslationProgressSnapshot.kt`; `BatchPhase.kt` deleted; no rename (the `PipelineStage` question stays open). |

### (c) Landed commits and gates

| Commit | Scope | Diff | Measured result | Gate |
|---|---|---|---|---|
| `fe6fa840` | Split `ChapterArtifactEngine` into engine + candidate transactions + rekey + retention | 4 files, +1,141 / −1,126 | Engine 1,258 → 283 code; `ChapterCandidateTransactions` 606; `ChapterArtifactRekey` 190; `ArtifactRetention` 159 | **287 suites / 1,784 tests / 0 failures / 1 skipped** |
| `963fa551` | Page model split, mask regions, LiteRT asset manager, dead-surface cleanup | 22 files, +989 / −1,047 | `PageTranslationView` 727 → 98 raw / 94 code; `PublishedPageTranslation` 369/340; `PageTranslationValueSemantics` 262/252; planner 989 → 838/~664; LiteRT 961 → 812/742; 3 files deleted, 2 store/lease methods + 1 manager method deleted | **287 suites / 1,784 tests / 0 failures / 1 skipped** |

Both gates were run by the parent with `:app:testStandardDebugUnitTest`; compile green, full Standard Debug unit suite green, only the existing synthetic journal benchmark skipped. CRLF-to-LF warnings on `git add` are expected and harmless.

### (d) Scorecard after the landed units

- **God files ≥700 code: 9 → 7.** Remaining: `TranslationTrace` 1,013 · `TranslationPipelineDiagnostics` 953 · `ReaderTranslationCoordinator` 853 · `TranslationProcessor` 852 · `QnnDiagnostics` 840 · `TranslationRequestCoordinator` 775 · `LiteRTMangaInpaintingEngine` 742.
- **Original 3b estimates:** LiteRT patch clusterer → ~657 (then 6 remain); TRC `PendingRequestLedger` T1 → ~560–595 (then 5 remain). Reader `ReaderOutcomeStore` R1 alone → ~825, so Reader still needs R2 and R3.
- **File count:** audit baseline 274; unit 1 added 3 files, wave 3a added 4 and deleted 3 (arithmetic 278 before 3b). 3b is adding 3 more; the exact recount is deferred until the 3b gate.
- **Comment cap: still NOT compliant.** Baseline scorecard: 917 over-cap runs (114 inline + 803 block). No new over-cap comment was committed: the parent removed a 5-line `TextLayoutMaskRegions` header and a 5-line `LiteRtInpaintingAssetManager` header before commit. The deleted `authorizeCleanedImageDeletion` KDoc (5 lines) removes at least one existing over-cap block. Exact recount pending.
- **Cleanup size:** dead-surface unit was 7 files, +20 / −89 (3 deletions, 4 modifications).

### (e) Coordinator seam design and 3b

- Read-only design delivered: `scratch/wave3-coordinator-seams.md` (219 lines). The post-cleanup audit gave no seams for the two coordinators, so this is a fresh dependency/lock-topology design.
- **ReaderTranslationCoordinator (853 code):** R1 `ReaderOutcomeStore` (−28, low risk, add retention/removeChapter tests), R2 `ReaderWorkerLoop` (−65, gateway + in-flight registry), R3 `ReaderViewportController` (−77, injected gates). R1+R2+R3 → ~683. Session binding/preparation and cancellation-controller extractions were rejected as redesigns; the optional session-switch unit alone misses the target.
- **TranslationRequestCoordinator (775 code):** T1 `PendingRequestLedger` (−180…−215, single unit reaches ~560–595), T2 `StartupReconciliation` (−70, optional after T1). Shape pins: the fence test's four fields move to the ledger; `pendingRequestMutationLock` and `pendingTranslationRequests` remain forwarding seams on TRC; startup entry points keep exact signatures; no constructor changes. The two compound lock sections (`beginHandoffIfCurrent`, retryRekey) must remain one lock acquisition each.
- **3b extractions (three disjoint children; gate and review passed):**
  1. LiteRT patch clusterer — `InpaintPatch` + `clusterIntoPatches` → `LiteRtPatchClusterer.kt`, pure move; expected engine ~657.
  2. Reader R1 — `ReaderOutcomeStore` + `ReaderOutcomeStoreTest`; expected ~825, first of three.
  3. TRC T1 — `PendingRequestLedger` + fence-test field relocation; expected ~560–595.

### (f) Open backlog / deferred

- Reader R2 and R3 are complete: `2291d339` and `4360c324`, respectively.
- TRC T2 `StartupReconciliation` optional after T1.
- Findings 3, 8, 9, 10, 11, 12 (OpenAI dedup, `toUiPauseReason`, store package move, `TranslationUiTruth` wrappers, `AiModelFetcher`, image GC extraction).
- D2 page-key collision migration; `TranslationProcessor` split / `executeColorizeOnly`; diagnostics condense (risk-flagged); colorization engine lease; batch failure semantics.
- Comment cap: exact recount after 3b; the module remains non-compliant on both the god-file threshold and the 3-line cap until the remaining god files and comment runs are cleared.
- Device validation unchanged: ORIGINAL colorization preset has no authorized device run; Manga Light on-device init stall unresolved; strict QNN HTP/GPU diagnostics still fail.
- **Git state:** HEAD `4360c324`; local only. No push without an explicit instruction.

### (g) Wave-3b worktree update

- All three extractions landed locally in `a73aebe7`: the LiteRT engine is 723 raw / 665 non-blank; Reader R1 is 941 raw / 828 code; TRC T1 is approximately 532 code, below 700. Reader still requires R2 and R3.
- The initial combined gate failed at Kotlin compilation: `TranslationManagerCoordinators` uses a callable reference to `clearAllPendingTranslationRequests`, which the T1 extraction removed. The coordinator facade was restored to delegate to the ledger and the same gate was restarted. The rerun passed: 288 suites / 1,787 tests / 0 failures / 0 errors / 1 skipped. Independent review passed with no actionable findings; it checked behavior preservation, ledger lock identity, compound transitions, generation fencing, and the restored callable-reference facade.
- The ledger's newly introduced over-cap comment blocks were shortened. Comment-cap and remaining god-file compliance are still open.

- Wave 3b commit: `a73aebe7` (8 files, +602 / -477); local only. Gate and independent review passed.

### (h) Reader R2 worktree update

- `ReaderWorkerLoop` extraction landed locally in `2291d339`; the coordinator measures approximately 758 code lines and still needs R3. Claim and in-flight registration remain inside the same coordinator mutex; shutdown preserves the original queue and job cleanup order.
- The first gate failed Kotlin compilation on recursive inference of the worker field. Giving that field an explicit `ReaderWorkerLoop` type fixed the error; the full rerun passed: 288 suites / 1,787 tests / 0 failures / 0 errors / 1 skipped. The review findings and final verification are recorded below.

- Independent review identified a removed `startWorker()` facade and an overlong line. The facade and original startup contract were restored, the line was wrapped, and a delayed-start/idempotence regression test was added. The reviewer verified both fixes and found no further issues. The final gate passed: 288 suites / 1,788 tests / 0 failures / 0 errors / 1 skipped.

### (i) Reader R3 worktree update

- `ReaderViewportController` landed locally in `4360c324`; coordinator measures 812 raw / 695 code lines. Live session-binding iteration, revision fences and original lifecycle clearing remain intact.
- The initial gate failed to compile because the token overload called an inaccessible callback instead of the coordinator's chapter-key overload. The original delegation was restored; the rerun passed 288 suites / 1,788 tests / 0 failures / 0 errors / 1 skipped. Independent review passed with no actionable findings.

### (j) Current size checkpoint

- At `4360c324`, scanning 276 Kotlin files under `app/src/main/java/eu/kanade/translation` and excluding blank and comment-only lines yields four files at or above 700 code lines: `TranslationTrace` 1,013; `TranslationPipelineDiagnostics` 953; `TranslationProcessor` 852; `QnnDiagnostics` 840. Each is previously deferred. Reader is 695; all currently non-deferred files are below the threshold.
- The comment-cap backlog remains open. A preliminary scan of standalone comment runs finds 912 over-cap runs (113 line-comment runs and 799 block comments); this is a heuristic and is not directly comparable to the earlier 917-run baseline without confirming its methodology. No device validation is implied by the JVM gates.

### (k) File-size limit revised

- The user revised the upper limit to 1,000 lines on 2026-11-14, superseding the earlier 700-line threshold. Applying the same code-line counting method as the prior checkpoint, only `TranslationTrace` (1,013) exceeds 1,000; `TranslationPipelineDiagnostics` (953), `TranslationProcessor` (852), and `QnnDiagnostics` (840) are below the limit. The previously recorded risk and deferral of diagnostics redesign still apply.
- Comment-cap cleanup remains a separate, active backlog.

### (l) Maintainability takes priority

- The user clarified that file size is a signal, not a goal by itself: a split must have a coherent architectural responsibility and improve readability for developers new to the project. Avoid creating small files solely to reduce line counts; preserve useful concurrency and lifecycle rationale when shortening comments. The 1,000-line upper limit remains the stated guideline.

### (m) Pending-request comment cleanup

- Six over-cap comments in the request coordinator and pending-request store were condensed without changing code. Five pre-existing longer comments were retained because they explain admission, cancellation, or persistence invariants; they remain open against the comment cap and have not been approved as exceptions.

### (n) Batch coordinator comment cleanup

- Seventeen comment blocks were shortened across six coordinator files, without changing code. Ten existing longer blocks in the seven-file scope were retained because they document decision tables, cancellation/lock contracts, and persistence behavior; they remain open against the comment cap.

### (o) Reader and progress comment cleanup

- Twelve comment blocks were shortened across five coordinator files, without changing code. Five existing longer explanations in the six-file scope remain open where they carry source-loading, finality, or durable-read context that should not be lost.
