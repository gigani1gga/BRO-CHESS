package com.example.chesslab.train

import com.example.chesslab.engine.Tables.NF
import com.example.chesslab.engine.Weights
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

data class TrainStats(
    val rate: Int = 0,
    val total: Long = 0,
    val matePercent: Int = 0,
    val winsA: Long = 0,
    val winsB: Long = 0,
    val valuesA: String = "–",
    val valuesB: String = "–"
)

enum class GameKind { LAST, BEST, WORST }

/**
 * Two engines (A and B) learn from each other on several threads. Each worker trains private copies of the
 * weights for ~250 ms, then its change (delta) is merged into the shared weights.
 */
class SelfPlayTrainer(
    private val scope: CoroutineScope,
    private val threads: Int,
    initialA: DoubleArray? = null,
    initialB: DoubleArray? = null
) {
    private val lock = Any()
    private var gA = initialA?.copyOf() ?: Weights.random()
    private var gB = initialB?.copyOf() ?: Weights.random()
    private var epoch = 0
    private var total = 0L
    private var tA = 0L
    private var tB = 0L
    private var tM = 0L
    private val gamesSinceTick = AtomicLong()

    @Volatile private var bestG: GameRecord? = null
    @Volatile private var worstG: GameRecord? = null
    @Volatile private var lastG: GameRecord? = null

    val running = MutableStateFlow(true)
    private val _stats = MutableStateFlow(TrainStats())
    val stats: StateFlow<TrainStats> = _stats

    private val executor = Executors.newFixedThreadPool(threads) { r ->
        Thread(r, "selfplay").apply { isDaemon = true }
    }
    private val dispatcher = executor.asCoroutineDispatcher()

    fun start() {
        repeat(threads) { k -> scope.launch(dispatcher) { workerLoop(k) } }
        scope.launch { tickerLoop() }
    }

    fun close() {
        running.value = false
        executor.shutdownNow()
    }

    fun game(kind: GameKind): GameRecord? = when (kind) {
        GameKind.LAST -> lastG
        GameKind.BEST -> bestG
        GameKind.WORST -> worstG
    }

    /** Copy of the currently stronger engine's weights and its label. */
    fun strongest(): Pair<Char, DoubleArray> = synchronized(lock) {
        if (tA >= tB) 'A' to gA.copyOf() else 'B' to gB.copyOf()
    }

    fun snapshot(): Pair<DoubleArray, DoubleArray> = synchronized(lock) { gA.copyOf() to gB.copyOf() }

    fun reset() = synchronized(lock) {
        gA = Weights.random()
        gB = Weights.random()
        epoch++
        total = 0; tA = 0; tB = 0; tM = 0
        bestG = null; worstG = null; lastG = null
    }

    private suspend fun workerLoop(k: Int) {
        val worker = SelfPlayWorker(System.nanoTime() + k * 7919L)
        val a = DoubleArray(NF)
        val b = DoubleArray(NF)
        val basisA = DoubleArray(NF)
        val basisB = DoubleArray(NF)
        while (coroutineContext.isActive) {
            running.first { it }
            var myEpoch: Int
            var scale: Double
            synchronized(lock) {
                System.arraycopy(gA, 0, a, 0, NF); System.arraycopy(gB, 0, b, 0, NF)
                System.arraycopy(gA, 0, basisA, 0, NF); System.arraycopy(gB, 0, basisB, 0, NF)
                myEpoch = epoch
                scale = maxOf(0.25, 1.0 / (1.0 + total / 30000.0))
            }
            val r = worker.runBatch(a, b, BATCH_MS, scale)
            synchronized(lock) {
                if (myEpoch == epoch) {
                    for (i in 0 until NF) {
                        gA[i] += a[i] - basisA[i]
                        gB[i] += b[i] - basisB[i]
                    }
                    total += r.games; tA += r.aWins; tB += r.bWins; tM += r.mates
                    val bg = bestG
                    if (r.best != null && (bg == null || r.best.plies <= bg.plies)) bestG = r.best
                    val wg = worstG
                    if (r.worst != null && (wg == null || r.worst.plies >= wg.plies)) worstG = r.worst
                    if (r.last != null) lastG = r.last
                    gamesSinceTick.addAndGet(r.games.toLong())
                }
            }
            kotlinx.coroutines.yield()
        }
    }

    private suspend fun tickerLoop() {
        var last = System.nanoTime()
        while (coroutineContext.isActive) {
            delay(1000)
            val now = System.nanoTime()
            val rate = gamesSinceTick.getAndSet(0) / ((now - last) / 1e9)
            last = now
            val s = synchronized(lock) {
                TrainStats(
                    rate = Math.round(rate).toInt(),
                    total = total,
                    matePercent = if (total > 0) Math.round(100.0 * tM / total).toInt() else 0,
                    winsA = tA,
                    winsB = tB,
                    valuesA = Weights.describe(gA),
                    valuesB = Weights.describe(gB)
                )
            }
            _stats.value = s
        }
    }

    private companion object {
        const val BATCH_MS = 250L
    }
}
