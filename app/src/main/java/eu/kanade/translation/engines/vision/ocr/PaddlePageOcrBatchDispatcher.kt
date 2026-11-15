package eu.kanade.translation.engines.vision.ocr

import eu.kanade.translation.engines.vision.ocr.paddle.batch.PaddleOcrBatch
import eu.kanade.translation.engines.vision.ocr.paddle.batch.PaddleOcrBatchPlanner
import eu.kanade.translation.engines.vision.ocr.paddle.batch.PaddleOcrBatchSize
import eu.kanade.translation.engines.vision.ocr.paddle.batch.PaddleOcrLeafIdentity
import eu.kanade.translation.engines.vision.ocr.paddle.batch.PaddleOcrLeafWork
import eu.kanade.translation.engines.vision.ocr.paddle.batch.PaddleOcrPageGeneration
import eu.kanade.translation.engines.vision.ocr.paddle.batch.PaddleOcrWidthBucket
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.CancellationException
/** One bounded, page-scoped recognizer call for diagnostics and tests. */
internal data class PaddlePageBatchTrace(
    val sequence: Long,
    val pageGeneration: PaddleOcrPageGeneration,
    val widthBucket: PaddleOcrWidthBucket,
    val leafIdentities: List<PaddleOcrLeafIdentity>,
    val requestedBatchSize: PaddleOcrBatchSize,
    val requestedChunkSize: Int = requestedBatchSize.value,
    val queueWaitMs: Double,
    val admissionWaitMs: Double,
    val batchLatencyMs: Double,
)

/**
 * Generic page dispatcher. The planner stays single-thread-confined here; the
 * shared mutex is the explicit seam guard for the engine session's one output
 * buffer. It is intentionally independent of nativeGuard, which remains owned
 * by RoiPageRecognitionEngine around the complete page.
 */
internal class PaddlePageOcrBatchDispatcher<CROP, RESULT>(
    private val pageGeneration: PaddleOcrPageGeneration,
    private val policy: PaddlePageOcrPolicy,
    private val recognizeBatch: suspend (
        crops: List<CROP>,
        widthBucket: PaddleOcrWidthBucket,
        maxBatch: Int,
    ) -> List<RESULT>,
    private val batchCallMutex: Mutex,
    private val nowNanos: () -> Long = System::nanoTime,
    private val traceSequence: () -> Long = { 0L },
    private val executorLatencyMs: () -> Double? = { null },
) {
    private val planner = PaddleOcrBatchPlanner<CROP>(
        pageGeneration,
        policy.validatedBatchSize,
        chunkSizeFor = { bucket ->
            policy.dynamicCeiling?.invoke(bucket) ?: policy.validatedBatchSize.value
        },
    )
    private val admittedAtNanos = LinkedHashMap<PaddleOcrLeafIdentity, Long>()
    private val resultsByIdentity = LinkedHashMap<PaddleOcrLeafIdentity, RESULT>()
    private val traces = ArrayList<PaddlePageBatchTrace>()

    val results: Map<PaddleOcrLeafIdentity, RESULT>
        get() = resultsByIdentity.toMap()

    val batchTraces: List<PaddlePageBatchTrace>
        get() = traces.toList()

    val resolvedLeafCount: Int
        get() = resultsByIdentity.size

    suspend fun submit(leaf: PaddleOcrLeafWork<CROP>) {
        val admittedAt = nowNanos()
        admittedAtNanos[leaf.identity] = admittedAt
        try {
            planner.admit(leaf)?.let { batch -> execute(batch) }
        } catch (failure: Throwable) {
            if (!leaf.cropOwnership.isReleased) leaf.releaseCropOwnership()
            admittedAtNanos.remove(leaf.identity)
            throw failure
        }
    }

    suspend fun finish() {
        planner.finishPage().forEach { batch -> execute(batch) }
        check(planner.isDrained) { "Paddle page planner did not drain $pageGeneration" }
    }

    fun cancel() {
        planner.cancel()
        admittedAtNanos.clear()
    }

    private suspend fun execute(batch: PaddleOcrBatch<CROP>) {
        val firstAdmission = batch.leaves.minOfOrNull { admittedAtNanos[it.identity] ?: nowNanos() } ?: nowNanos()
        val beforeMutex = nowNanos()
        try {
            val rows = batchCallMutex.withLock {
                recognizeBatch(batch.leaves.map { it.crop }, batch.widthBucket, batch.leaves.size)
            }
            val afterMutex = nowNanos()
            planner.complete(batch, rows) { leaf, result ->
                check(resultsByIdentity.put(leaf.identity, result) == null) {
                    "duplicate Paddle page result for ${leaf.identity}"
                }
                admittedAtNanos.remove(leaf.identity)
            }
            traces += PaddlePageBatchTrace(
                sequence = traceSequence(),
                pageGeneration = pageGeneration,
                widthBucket = batch.widthBucket,
                leafIdentities = batch.leaves.map { it.identity },
                requestedBatchSize = policy.validatedBatchSize,
                requestedChunkSize = batch.size,
                queueWaitMs = nanosToMs(beforeMutex - firstAdmission),
                admissionWaitMs = nanosToMs(afterMutex - beforeMutex),
                batchLatencyMs = executorLatencyMs()?.takeIf { it.isFinite() && it >= 0.0 }
                    ?: nanosToMs(afterMutex - beforeMutex),
            )
        } catch (cancelled: CancellationException) {
            if (!batch.isComplete) planner.release(batch)
            planner.cancel()
            admittedAtNanos.clear()
            throw cancelled
        } catch (failure: Throwable) {
            if (!batch.isComplete) planner.release(batch)
            planner.cancel()
            admittedAtNanos.clear()
            throw failure
        }
    }

    private fun nanosToMs(value: Long): Double = value.coerceAtLeast(0L) / 1_000_000.0
}
