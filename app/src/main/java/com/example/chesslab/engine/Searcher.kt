package com.example.chesslab.engine

import kotlin.math.abs

class SearchResult(
    @JvmField val move: Int,      // packed move, or -1 when there is no legal move
    @JvmField val score: Int,     // centipawns from the side to move's point of view
    @JvmField val depth: Int,     // last fully completed iteration
    @JvmField val nodes: Long
)

/**
 * Alpha-beta searcher: iterative deepening, principal-variation search, transposition table,
 * quiescence with check evasions, killer/history/MVV-LVA move ordering, late move reductions,
 * check extensions and repetition/50-move draw detection.
 *
 * The evaluation blends a classical term (material, piece-square tables, pawn structure, bishop pair)
 * with the engine's self-learned piece-square values once those have become meaningful.
 *
 * It works directly on [e] (make/unmake restore the position). NOT thread-safe: one per engine.
 */
class Searcher(private val e: Engine, ttBits: Int = 18) {

    private val ttSize = 1 shl ttBits
    private val ttMask = ttSize - 1
    private val ttKey = LongArray(ttSize)
    private val ttMove = IntArray(ttSize)
    private val ttScore = IntArray(ttSize)
    private val ttDepth = ByteArray(ttSize)
    private val ttFlag = ByteArray(ttSize)          // 0 empty, 1 exact, 2 lower bound, 3 upper bound

    private val mv = Array(MAXD + 4) { IntArray(320) }
    private val sc = Array(MAXD + 4) { IntArray(320) }
    private val killers = Array(MAXD + 4) { IntArray(2) }
    private val history = Array(2) { IntArray(4096) }
    private val path = LongArray(MAXD + 4)

    private val vv = DoubleArray(840)
    private var mix = 0.0
    private var unit = 1.0

    private val wCnt = IntArray(8)
    private val bCnt = IntArray(8)
    private val wMin = IntArray(8)
    private val wMax = IntArray(8)
    private val bMin = IntArray(8)
    private val bMax = IntArray(8)

    private var nodes = 0L
    private var deadline = 0L
    private var stopped = false
    @Volatile private var abortFlag = false

    /** Installs the learned weights (null = classical evaluation only) and clears the table. */
    fun setWeights(w: DoubleArray?) {
        if (w == null) {
            mix = 0.0
        } else {
            Weights.buildValues(w, vv)
            val pu = Weights.pieceValue(w, 1)
            val trust = ((pu - 0.05) / 0.10).coerceIn(0.0, 1.0)
            mix = 0.5 * trust
            unit = if (pu > 0.02) pu else 1.0
        }
        ttFlag.fill(0)
    }

    /** Asks a running [best] call (on another thread) to finish as soon as possible. */
    fun abort() { abortFlag = true }

    // ------------------------------------------------------------------ iterative deepening

    fun best(maxMs: Long, maxDepth: Int = 32): SearchResult {
        abortFlag = false
        nodes = 0
        stopped = false
        val t0 = System.nanoTime()
        deadline = t0 + maxMs * 1_000_000L
        for (k in killers) { k[0] = 0; k[1] = 0 }
        for (h in history) h.fill(0)

        val s = e.side
        val root = mv[0]
        var cnt = e.gen(root)
        var legal = 0
        for (i in 0 until cnt) {
            val m = root[i]
            e.make(m)
            val ok = !e.att(e.kingSq(s), -s)
            e.unmake(m)
            if (ok) root[legal++] = m
        }
        cnt = legal
        if (cnt == 0) return SearchResult(-1, if (e.inCheck()) -MATE else 0, 0, 0)
        var bestMove = root[0]
        if (cnt == 1) return SearchResult(bestMove, 0, 0, 0)

        var bestScore = 0
        var doneDepth = 0
        var prevBest = 0
        path[0] = e.hash64()
        val soft = maxMs * 1_000_000L * 45 / 100

        for (d in 1..maxDepth) {
            scoreMoves(0, cnt, prevBest)
            var alpha = -INF
            var iterBest = -1
            var iterScore = -INF
            for (i in 0 until cnt) {
                pickBest(root, sc[0], cnt, i)
                val m = root[i]
                e.make(m)
                path[1] = e.hash64()
                var v: Int
                if (i == 0) {
                    v = -search(d - 1, -INF, -alpha, 1)
                } else {
                    v = -search(d - 1, -alpha - 1, -alpha, 1)
                    if (v > alpha && !stopped) v = -search(d - 1, -INF, -alpha, 1)
                }
                e.unmake(m)
                if (stopped) break
                if (v > iterScore) { iterScore = v; iterBest = m }
                if (v > alpha) alpha = v
            }
            if (stopped) {
                if (doneDepth == 0 && iterBest != -1) { bestMove = iterBest; bestScore = iterScore }
                break
            }
            bestMove = iterBest
            bestScore = iterScore
            doneDepth = d
            prevBest = iterBest
            if (abs(bestScore) > MATE - 256) break
            if (System.nanoTime() - t0 > soft) break
        }
        return SearchResult(bestMove, bestScore, doneDepth, nodes)
    }

    // ------------------------------------------------------------------ main search

    private fun search(depth0: Int, alpha0: Int, beta: Int, ply: Int): Int {
        if ((++nodes and 2047L) == 0L && (abortFlag || System.nanoTime() > deadline)) stopped = true
        if (stopped) return 0
        if (e.hm >= 100 || repeated(ply)) return 0
        if (ply >= MAXD) return evaluate()

        val s = e.side
        val inChk = e.att(e.kingSq(s), -s)
        var depth = depth0
        if (inChk) depth++
        if (depth <= 0) return qsearch(alpha0, beta, ply, 0)

        var alpha = alpha0
        val key = path[ply]
        val idx = ((key xor (key ushr 29)).toInt()) and ttMask
        var ttm = 0
        if (ttFlag[idx].toInt() != 0 && ttKey[idx] == key) {
            ttm = ttMove[idx]
            if (ttDepth[idx] >= depth) {
                var v = ttScore[idx]
                if (v > MATE - 256) v -= ply else if (v < -MATE + 256) v += ply
                when (ttFlag[idx].toInt()) {
                    1 -> return v
                    2 -> if (v >= beta) return v
                    3 -> if (v <= alpha) return v
                }
            }
        }

        val list = mv[ply]
        val scr = sc[ply]
        val n = e.gen(list)
        scoreMoves(ply, n, ttm)

        var best = -INF
        var bestM = 0
        var legal = 0
        for (i in 0 until n) {
            pickBest(list, scr, n, i)
            val m = list[i]
            val cap = e.b[(m shr 6) and 63] != 0 || ((m shr 15) and 1) != 0
            val promo = ((m shr 12) and 7) != 0
            e.make(m)
            if (e.att(e.kingSq(s), -s)) { e.unmake(m); continue }
            legal++
            path[ply + 1] = e.hash64()
            val nd = depth - 1
            var v: Int
            if (legal == 1) {
                v = -search(nd, -beta, -alpha, ply + 1)
            } else {
                val red = if (depth >= 3 && legal > 4 && !cap && !promo && !inChk) 1 else 0
                v = -search(nd - red, -alpha - 1, -alpha, ply + 1)
                if (red > 0 && v > alpha && !stopped) v = -search(nd, -alpha - 1, -alpha, ply + 1)
                if (v > alpha && v < beta && !stopped) v = -search(nd, -beta, -alpha, ply + 1)
            }
            e.unmake(m)
            if (stopped) return 0
            if (v > best) { best = v; bestM = m }
            if (v > alpha) {
                alpha = v
                if (alpha >= beta) {
                    if (!cap) {
                        val k = killers[ply]
                        if (k[0] != m) { k[1] = k[0]; k[0] = m }
                        val h = history[(s + 1) shr 1]
                        val hi = (m and 63) * 64 + ((m shr 6) and 63)
                        h[hi] = minOf(60000, h[hi] + depth * depth)
                    }
                    break
                }
            }
        }

        if (legal == 0) return if (inChk) -MATE + ply else 0

        val flag = if (best <= alpha0) 3 else if (best >= beta) 2 else 1
        var sv = best
        if (sv > MATE - 256) sv += ply else if (sv < -MATE + 256) sv -= ply
        ttKey[idx] = key
        ttMove[idx] = bestM
        ttScore[idx] = sv
        ttDepth[idx] = depth.toByte()
        ttFlag[idx] = flag.toByte()
        return best
    }

    private fun qsearch(alpha0: Int, beta: Int, ply: Int, qd: Int): Int {
        if ((++nodes and 2047L) == 0L && (abortFlag || System.nanoTime() > deadline)) stopped = true
        if (stopped) return 0
        if (ply >= MAXD) return evaluate()

        val s = e.side
        val inChk = e.att(e.kingSq(s), -s)
        var alpha = alpha0
        var best: Int
        if (!inChk) {
            best = evaluate()
            if (best >= beta || qd > 10) return best
            if (best > alpha) alpha = best
        } else {
            best = -MATE + ply
        }

        val list = mv[ply]
        val scr = sc[ply]
        val total = e.gen(list)
        var n = 0
        for (i in 0 until total) {
            val m = list[i]
            if (inChk) { list[n++] = m; continue }
            val promo = (m shr 12) and 7
            if (e.b[(m shr 6) and 63] != 0 || ((m shr 15) and 1) != 0 || promo == 5) list[n++] = m
        }
        scoreMoves(ply, n, 0)

        var legal = 0
        for (i in 0 until n) {
            pickBest(list, scr, n, i)
            val m = list[i]
            e.make(m)
            if (e.att(e.kingSq(s), -s)) { e.unmake(m); continue }
            legal++
            val v = -qsearch(-beta, -alpha, ply + 1, qd + 1)
            e.unmake(m)
            if (stopped) return 0
            if (v > best) best = v
            if (v > alpha) {
                alpha = v
                if (alpha >= beta) break
            }
        }
        if (inChk && legal == 0) return -MATE + ply
        return best
    }

    // ------------------------------------------------------------------ move ordering

    private fun scoreMoves(ply: Int, n: Int, ttm: Int) {
        val list = mv[ply]
        val scr = sc[ply]
        val k = killers[ply]
        val h = history[(e.side + 1) shr 1]
        val b = e.b
        for (i in 0 until n) {
            val m = list[i]
            val from = m and 63
            val to = (m shr 6) and 63
            val victim = abs(b[to])
            val promo = (m shr 12) and 7
            scr[i] = when {
                m == ttm -> 1_000_000
                victim != 0 -> 100_000 + 10 * victim - abs(b[from])
                ((m shr 15) and 1) != 0 -> 100_009
                promo != 0 -> 90_000 + promo
                m == k[0] -> 80_000
                m == k[1] -> 79_000
                else -> h[from * 64 + to]
            }
        }
    }

    private fun pickBest(list: IntArray, scr: IntArray, n: Int, from: Int) {
        var bi = from
        var bs = scr[from]
        for (j in from + 1 until n) {
            if (scr[j] > bs) { bs = scr[j]; bi = j }
        }
        if (bi != from) {
            val tm = list[from]; list[from] = list[bi]; list[bi] = tm
            val ts = scr[from]; scr[from] = scr[bi]; scr[bi] = ts
        }
    }

    /** True when the current position already occurred earlier in the search path or the played game. */
    private fun repeated(ply: Int): Boolean {
        val key = path[ply]
        var j = 2
        val lim = e.hm
        while (j <= lim) {
            val p: Long
            if (j <= ply) {
                p = path[ply - j]
            } else {
                val ri = e.repN - 1 - (j - ply)
                if (ri < 0) break
                p = e.rep[ri]
            }
            if (p == key) return true
            j += 2
        }
        return false
    }

    // ------------------------------------------------------------------ evaluation

    /** Score in centipawns from the side to move's point of view. */
    private fun evaluate(): Int {
        val b = e.b
        wCnt.fill(0); bCnt.fill(0)
        wMin.fill(8); bMin.fill(8)
        wMax.fill(-1); bMax.fill(-1)
        var cl = 0
        var ls = 0.0
        var nonPawn = 0
        var wB = 0
        var bB = 0
        val useLearned = mix > 0.0

        for (sq in 0 until 64) {
            val p = b[sq]
            if (p == 0) continue
            val a = if (p > 0) 1 else -1
            val q = p * a
            if (useLearned) ls += vv[(p + 6) * 64 + sq]
            if (q == 6) continue
            val o = if (a > 0) sq else sq xor 56
            cl += a * (VAL[q] + PST[q][o])
            val f = sq and 7
            val r = sq shr 3
            if (q == 1) {
                if (a > 0) {
                    wCnt[f]++
                    if (r < wMin[f]) wMin[f] = r
                    if (r > wMax[f]) wMax[f] = r
                } else {
                    bCnt[f]++
                    if (r < bMin[f]) bMin[f] = r
                    if (r > bMax[f]) bMax[f] = r
                }
            } else {
                nonPawn += VAL[q]
                if (q == 3) { if (a > 0) wB++ else bB++ }
            }
        }

        for (f in 0 until 8) {
            if (wCnt[f] > 1) cl -= 10 * (wCnt[f] - 1)
            if (bCnt[f] > 1) cl += 10 * (bCnt[f] - 1)
            if (wCnt[f] > 0) {
                val rw = wMin[f]
                var passed = true
                for (g in maxOf(0, f - 1)..minOf(7, f + 1)) if (bMin[g] < rw) { passed = false; break }
                if (passed) cl += PASS[7 - rw]
            }
            if (bCnt[f] > 0) {
                val rb = bMax[f]
                var passed = true
                for (g in maxOf(0, f - 1)..minOf(7, f + 1)) if (wMax[g] > rb) { passed = false; break }
                if (passed) cl -= PASS[rb]
            }
        }

        val kt = if (nonPawn < 2600) KEG else PST[6]
        cl += kt[e.ks[0]] - kt[e.ks[1] xor 56]
        if (wB >= 2) cl += 30
        if (bB >= 2) cl -= 30

        var score = cl.toDouble()
        if (useLearned) {
            val lc = (ls / unit * 100.0).coerceIn(-1500.0, 1500.0)
            score = score * (1.0 - mix) + lc * mix
        }
        val st = score.toInt()
        return (if (e.side == 1) st else -st) + 8
    }

    private companion object {
        const val MAXD = 64
        const val MATE = 30000
        const val INF = 32000

        val VAL = intArrayOf(0, 100, 320, 330, 500, 900, 0)
        val PASS = intArrayOf(0, 5, 10, 20, 35, 60, 100, 0)
        val PST = Array(7) { IntArray(64) }
        val KEG = IntArray(64)

        init {
            for (s in 0 until 64) {
                val r = s shr 3
                val f = s and 7
                val adv = 7 - r                       // 0..7 progress for white
                val cf = minOf(f, 7 - f)
                val cr = minOf(r, 7 - r)
                val cen = cf + cr
                PST[1][s] = if (adv in 1..6) (adv - 1) * 6 + (if (cf >= 2) minOf(adv, 4) * 3 else 0) else 0
                PST[2][s] = cen * 7 - (if (cf == 0) 10 else 0)
                PST[3][s] = cen * 4
                PST[4][s] = (if (adv == 6) 15 else 0) + (if (cf >= 2) 5 else 0)
                PST[5][s] = cen * 2
                PST[6][s] = -adv * 18 + (if (adv == 0 && (f == 6 || f == 2 || f == 1)) 28 else 0)
                KEG[s] = cen * 12
            }
        }
    }
}
