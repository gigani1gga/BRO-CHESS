package com.example.chesslab.engine

import com.example.chesslab.engine.Tables.CM
import com.example.chesslab.engine.Tables.KI
import com.example.chesslab.engine.Tables.KN
import com.example.chesslab.engine.Tables.MAT
import com.example.chesslab.engine.Tables.MAXP
import com.example.chesslab.engine.Tables.OW
import com.example.chesslab.engine.Tables.RY
import com.example.chesslab.engine.Tables.TM
import com.example.chesslab.engine.Tables.ZOB
import kotlin.math.abs
import kotlin.math.tanh

/** Reusable per-engine buffer that holds the game most recently produced by [Engine.play]. */
class GameBuf {
    @JvmField val moves = IntArray(MAXP)
    @JvmField val boards = IntArray(MAXP * 64)   // board after each ply
    @JvmField var plies = 0
    @JvmField var z = 0.0                        // +1 white mates, -1 black mates, small value = material
    @JvmField var mate = false
    @JvmField var aWhite = false
}

/**
 * Bitboard-free mailbox engine tuned for allocation-free self-play.
 * Moves are packed ints: from | to << 6 | promotion << 12 | flags << 15 (1 = en passant, 2 = castling).
 * NOT thread-safe: use one instance per thread.
 */
class Engine(seed: Long = System.nanoTime()) {

    @JvmField val b = IntArray(64)
    @JvmField internal val ks = IntArray(2)  // king squares: [white, black]
    var side = 1
        private set
    internal var cs = 15
    internal var ep = -1
    internal var hm = 0
    private var sp = 0

    /** 64-bit hashes of every position played with [apply] (used for repetition detection in search). */
    @JvmField internal val rep = LongArray(1024)
    @JvmField internal var repN = 0

    private val us = IntArray(7 * 320)       // undo stack, 7 ints per ply
    private val m1 = IntArray(512)
    private val m2 = IntArray(512)
    private val lm = IntArray(512)
    private val oa = IntArray(64)            // squares the opponent attacks
    private val ma = IntArray(64)            // squares we defend
    private val pn = IntArray(64)            // our pinned pieces
    private val ds = IntArray(64)            // our pieces that would discover a check
    private val dc = IntArray(64)            // squares giving direct check
    private val vw = DoubleArray(840)
    private val vb = DoubleArray(840)
    private val tv = DoubleArray(MAXP)
    private val hist = IntArray(MAXP + 2)

    private var rng = seed or 1L

    private fun rnd(): Double {
        var x = rng
        x = x xor (x shl 13)
        x = x xor (x ushr 7)
        x = x xor (x shl 17)
        rng = x
        return (x ushr 11).toDouble() * (1.0 / 9007199254740992.0)
    }

    init { reset() }

    // ------------------------------------------------------------------ position

    fun reset() {
        val k = intArrayOf(4, 2, 3, 5, 6, 3, 2, 4)
        b.fill(0)
        for (f in 0 until 8) {
            b[f] = -k[f]; b[8 + f] = -1; b[48 + f] = 1; b[56 + f] = k[f]
        }
        side = 1; cs = 15; ep = -1; hm = 0; sp = 0
        ks[0] = 60; ks[1] = 4
        repN = 0; pushRep()
    }

    fun setFen(fen: String) {
        val parts = fen.trim().split(" ")
        b.fill(0)
        var i = 0
        for (ch in parts[0]) {
            if (ch == '/') continue
            if (ch in '1'..'8') { i += ch - '0'; continue }
            val k = "pnbrqk".indexOf(ch.lowercaseChar()) + 1
            val white = ch.isUpperCase()
            b[i] = if (white) k else -k
            if (k == 6) ks[if (white) 0 else 1] = i
            i++
        }
        side = if (parts.getOrElse(1) { "w" } == "w") 1 else -1
        val c = parts.getOrElse(2) { "-" }
        cs = (if ('K' in c) 1 else 0) or (if ('Q' in c) 2 else 0) or
            (if ('k' in c) 4 else 0) or (if ('q' in c) 8 else 0)
        ep = if (parts.size > 3 && parts[3] != "-")
            "abcdefgh".indexOf(parts[3][0]) + (8 - (parts[3][1] - '0')) * 8 else -1
        hm = parts.getOrNull(4)?.toIntOrNull() ?: 0
        sp = 0
        repN = 0; pushRep()
    }

    fun fen(): String {
        val sb = StringBuilder(80)
        for (r in 0 until 8) {
            var n = 0
            for (f in 0 until 8) {
                val p = b[r * 8 + f]
                if (p == 0) { n++; continue }
                if (n > 0) { sb.append(n); n = 0 }
                val c = "pnbrqk"[abs(p) - 1]
                sb.append(if (p > 0) c.uppercaseChar() else c)
            }
            if (n > 0) sb.append(n)
            if (r < 7) sb.append('/')
        }
        sb.append(if (side == 1) " w " else " b ")
        val start = sb.length
        if ((cs and 1) != 0) sb.append('K')
        if ((cs and 2) != 0) sb.append('Q')
        if ((cs and 4) != 0) sb.append('k')
        if ((cs and 8) != 0) sb.append('q')
        if (sb.length == start) sb.append('-')
        sb.append(' ')
        if (ep >= 0) sb.append("abcdefgh"[ep and 7]).append(8 - (ep shr 3)) else sb.append('-')
        sb.append(' ').append(hm).append(" 1")
        return sb.toString()
    }

    private fun kidx(s: Int) = if (s == 1) 0 else 1

    // ------------------------------------------------------------------ attacks

    internal fun att(sq: Int, by: Int): Boolean {
        for (t in KN[sq]) if (b[t] == 2 * by) return true
        for (t in KI[sq]) if (b[t] == 6 * by) return true
        val r = sq shr 3
        val f = sq and 7
        val pr = r + by
        if (pr in 0..7) {
            if (f > 0 && b[pr * 8 + f - 1] == by) return true
            if (f < 7 && b[pr * 8 + f + 1] == by) return true
        }
        val rays = RY[sq]
        for (i in 0 until 8) {
            for (t in rays[i]) {
                val p = b[t]
                if (p != 0) {
                    val q = p * by
                    if (q > 0 && (q == 5 || (if (i < 4) q == 4 else q == 3))) return true
                    break
                }
            }
        }
        return false
    }

    fun inCheck(): Boolean = att(ks[kidx(side)], -side)

    // ------------------------------------------------------------------ move generation (pseudo-legal)

    internal fun gen(buf: IntArray): Int {
        var n = 0
        val s = side
        for (sq in 0 until 64) {
            val p = b[sq] * s
            if (p <= 0) continue
            val r = sq shr 3
            val f = sq and 7
            if (p == 1) {
                val d = -8 * s
                val t = sq + d
                val pr = (t shr 3) == (if (s == 1) 0 else 7)
                if (b[t] == 0) {
                    if (pr) {
                        for (q in 2..5) buf[n++] = sq or (t shl 6) or (q shl 12)
                    } else {
                        buf[n++] = sq or (t shl 6)
                        if (r == (if (s == 1) 6 else 1) && b[t + d] == 0) buf[n++] = sq or ((t + d) shl 6)
                    }
                }
                var c = -1
                while (c < 2) {
                    val ff = f + c
                    if (ff in 0..7) {
                        val to = t + c
                        if (b[to] * s < 0) {
                            if (pr) {
                                for (q in 2..5) buf[n++] = sq or (to shl 6) or (q shl 12)
                            } else buf[n++] = sq or (to shl 6)
                        } else if (to == ep && b[to] == 0) {
                            buf[n++] = sq or (to shl 6) or (1 shl 15)
                        }
                    }
                    c += 2
                }
            } else if (p == 2 || p == 6) {
                val l = if (p == 2) KN[sq] else KI[sq]
                for (t in l) if (b[t] * s <= 0) buf[n++] = sq or (t shl 6)
            } else {
                val a = if (p == 3) 4 else 0
                val e = if (p == 4) 4 else 8
                val rays = RY[sq]
                for (i in a until e) {
                    for (t in rays[i]) {
                        val x = b[t] * s
                        if (x > 0) break
                        buf[n++] = sq or (t shl 6)
                        if (x != 0) break
                    }
                }
            }
        }
        if (s == 1) {
            if ((cs and 1) != 0 && b[61] == 0 && b[62] == 0 && !att(60, -1) && !att(61, -1) && !att(62, -1))
                buf[n++] = 60 or (62 shl 6) or (2 shl 15)
            if ((cs and 2) != 0 && b[59] == 0 && b[58] == 0 && b[57] == 0 && !att(60, -1) && !att(59, -1) && !att(58, -1))
                buf[n++] = 60 or (58 shl 6) or (2 shl 15)
        } else {
            if ((cs and 4) != 0 && b[5] == 0 && b[6] == 0 && !att(4, 1) && !att(5, 1) && !att(6, 1))
                buf[n++] = 4 or (6 shl 6) or (2 shl 15)
            if ((cs and 8) != 0 && b[3] == 0 && b[2] == 0 && b[1] == 0 && !att(4, 1) && !att(3, 1) && !att(2, 1))
                buf[n++] = 4 or (2 shl 6) or (2 shl 15)
        }
        return n
    }

    // ------------------------------------------------------------------ make / unmake

    internal fun make(m: Int) {
        val f = m and 63
        val t = (m shr 6) and 63
        val pr = (m shr 12) and 7
        val fl = m shr 15
        val p = b[f]
        val o = sp
        sp += 7
        us[o] = b[t]; us[o + 1] = cs; us[o + 2] = ep; us[o + 3] = hm
        us[o + 4] = p; us[o + 5] = side; us[o + 6] = ks[0] or (ks[1] shl 8)
        val ap = if (p < 0) -p else p
        hm = if (ap == 1 || b[t] != 0) 0 else hm + 1
        b[t] = if (pr != 0) pr * side else p
        b[f] = 0
        if (ap == 6) ks[kidx(side)] = t
        if ((fl and 1) != 0) b[t + 8 * side] = 0
        if ((fl and 2) != 0) {
            if (t > f) { b[f + 1] = b[f + 3]; b[f + 3] = 0 } else { b[f - 1] = b[f - 4]; b[f - 4] = 0 }
        }
        ep = if (ap == 1 && (t - f == 16 || f - t == 16)) (f + t) shr 1 else -1
        cs = cs and CM[f] and CM[t]
        side = -side
    }

    internal fun unmake(m: Int) {
        val f = m and 63
        val t = (m shr 6) and 63
        val fl = m shr 15
        sp -= 7
        val o = sp
        side = us[o + 5]
        b[f] = us[o + 4]
        b[t] = us[o]
        cs = us[o + 1]; ep = us[o + 2]; hm = us[o + 3]
        val k = us[o + 6]
        ks[0] = k and 255; ks[1] = k shr 8
        if ((fl and 1) != 0) b[t + 8 * side] = -side
        if ((fl and 2) != 0) {
            if (t > f) { b[f + 3] = b[f + 1]; b[f + 1] = 0 } else { b[f - 4] = b[f - 1]; b[f - 1] = 0 }
        }
    }

    /** Plays a move permanently (no undo information is kept). */
    fun apply(m: Int) {
        make(m)
        sp -= 7
        pushRep()
    }

    /** Square of the king of colour [s] (1 white, -1 black). */
    internal fun kingSq(s: Int): Int = ks[if (s == 1) 0 else 1]

    /** 64-bit Zobrist-style key of the current position (pieces, side, castling, en passant file). */
    internal fun hash64(): Long {
        var h = if (side == 1) 0L else Tables.Z64[832]
        h = h xor Tables.Z64[833 + cs]
        if (ep >= 0) h = h xor Tables.Z64[849 + (ep and 7)]
        for (q in 0 until 64) {
            val p = b[q]
            if (p != 0) h = h xor Tables.Z64[(p + 6) * 64 + q]
        }
        return h
    }

    private fun pushRep() {
        if (repN == rep.size) {
            System.arraycopy(rep, rep.size / 2, rep, 0, rep.size / 2)
            repN = rep.size / 2
        }
        rep[repN++] = hash64()
    }

    /** Copies the full game state (position, rights, repetition history) from [o]. */
    fun copyFrom(o: Engine) {
        System.arraycopy(o.b, 0, b, 0, 64)
        ks[0] = o.ks[0]; ks[1] = o.ks[1]
        side = o.side; cs = o.cs; ep = o.ep; hm = o.hm; sp = 0
        System.arraycopy(o.rep, 0, rep, 0, o.repN)
        repN = o.repN
    }

    private fun hasLegal(): Boolean {
        val s = side
        val n = gen(m2)
        for (i in 0 until n) {
            val m = m2[i]
            make(m)
            val ok = !att(ks[kidx(s)], -s)
            unmake(m)
            if (ok) return true
        }
        return false
    }

    fun legalMoves(): IntArray {
        val s = side
        val n = gen(m1)
        val out = IntArray(n)
        var c = 0
        for (i in 0 until n) {
            val m = m1[i]
            make(m)
            if (!att(ks[kidx(s)], -s)) out[c++] = m
            unmake(m)
        }
        return out.copyOf(c)
    }

    fun randomLegalMove(): Int {
        val l = legalMoves()
        return if (l.isEmpty()) -1 else l[(rnd() * l.size).toInt()]
    }

    fun perft(depth: Int): Long {
        if (depth == 0) return 1L
        val buf = IntArray(512)
        val s = side
        val n = gen(buf)
        var total = 0L
        for (i in 0 until n) {
            val m = buf[i]
            make(m)
            if (!att(ks[kidx(s)], -s)) total += perft(depth - 1)
            unmake(m)
        }
        return total
    }

    // ------------------------------------------------------------------ move choice

    private fun amap(m: IntArray, sd: Int) {
        m.fill(0)
        for (q in 0 until 64) {
            val p = b[q] * sd
            if (p <= 0) continue
            if (p == 1) {
                val t = (q shr 3) - sd
                val f = q and 7
                if (t in 0..7) {
                    if (f > 0) m[t * 8 + f - 1] = 1
                    if (f < 7) m[t * 8 + f + 1] = 1
                }
            } else if (p == 2) {
                for (t in KN[q]) m[t] = 1
            } else if (p == 6) {
                for (t in KI[q]) m[t] = 1
            } else {
                val a = if (p == 3) 4 else 0
                val e = if (p == 4) 4 else 8
                val rays = RY[q]
                for (i in a until e) {
                    for (t in rays[i]) {
                        m[t] = 1
                        if (b[t] != 0) break
                    }
                }
            }
        }
    }

    /** Pieces of colour [fr] pinned to the king on [q] by a slider of colour [sl]. */
    private fun pins(q: Int, fr: Int, sl: Int, m: IntArray) {
        m.fill(0)
        val rays = RY[q]
        for (i in 0 until 8) {
            var x = -1
            for (t in rays[i]) {
                val p = b[t]
                if (p == 0) continue
                if (x < 0) {
                    if (p * fr > 0 && p * p < 36) x = t else break
                } else {
                    val a = p * sl
                    if (a > 0 && (a == 5 || (if (i < 4) a == 4 else a == 3))) m[x] = 1
                    break
                }
            }
        }
    }

    /** Squares from which a piece of side [s] would give direct check to the king on [q]. */
    private fun cks(q: Int, s: Int) {
        dc.fill(0)
        for (t in KN[q]) dc[t] = dc[t] or 2
        val r = (q shr 3) + s
        val f = q and 7
        if (r in 0..7) {
            if (f > 0) dc[r * 8 + f - 1] = dc[r * 8 + f - 1] or 1
            if (f < 7) dc[r * 8 + f + 1] = dc[r * 8 + f + 1] or 1
        }
        val rays = RY[q]
        for (i in 0 until 8) {
            for (t in rays[i]) {
                dc[t] = dc[t] or (if (i < 4) 8 else 4)
                if (b[t] != 0) break
            }
        }
    }

    /**
     * Greedy one-ply policy on learned values with hanging-piece pricing, mate-in-one detection and
     * epsilon-random exploration. Returns -1 when there is no legal move.
     */
    private fun pick(v: DoubleArray, eps: Double): Int {
        val s = side
        val ks0 = kidx(s)
        val n = gen(m1)
        val kq = ks[ks0]
        val oq = ks[1 - ks0]
        val chk = att(kq, -s)
        amap(oa, -s); amap(ma, s)
        pins(kq, s, -s, pn); pins(oq, s, s, ds)
        cks(oq, s)
        var c = 0
        var best = -1e9
        var bi = 0
        for (i in 0 until n) {
            val m = m1[i]
            val f = m and 63
            val t = (m shr 6) and 63
            val fl = m shr 15
            val pr = (m shr 12) and 7
            val p = b[f]
            val cp = b[t]
            val ap = p * s
            var d = v[((if (pr != 0) pr * s else p) + 6) * 64 + t] - v[(p + 6) * 64 + f] - v[(cp + 6) * 64 + t]
            if ((fl and 1) != 0) d -= v[(6 - s) * 64 + t + 8 * s]
            if ((fl and 2) != 0) {
                val rf = if (t > f) f + 3 else f - 4
                val rt = if (t > f) f + 1 else f - 1
                val base = (4 * s + 6) * 64
                d += v[base + rt] - v[base + rf]
            }
            var score = s * d + rnd() * 0.02
            if (ap < 6) {
                val a = v[832 + ap]
                if (oa[t] != 0) {
                    score -= if (ma[t] != 0) maxOf(0.0, a - v[832 + (if (cp < 0) -cp else cp)]) * 0.5 else a
                }
                if (oa[f] != 0 && ma[f] == 0) score += a
            }
            val full = chk || ap == 6 || fl != 0 || pn[f] != 0
            val gc = fl != 0 || ds[f] != 0 || (dc[t] and TM[if (pr != 0) pr else ap]) != 0
            if (full || gc) {
                make(m)
                if (full && att(ks[ks0], -s)) {
                    unmake(m)
                    continue
                }
                if (gc && att(ks[1 - ks0], s) && !hasLegal()) score = 1e3
                unmake(m)
            }
            lm[c] = m
            if (score > best) { best = score; bi = c }
            c++
        }
        if (c == 0) return -1
        return if (rnd() < eps) lm[(rnd() * c).toInt()] else lm[bi]
    }

    /** Best move for the side to move using weights [w] (exploration probability [eps]). */
    fun ai(w: DoubleArray, eps: Double): Int {
        Weights.buildValues(w, vw)
        return pick(vw, eps)
    }

    // ------------------------------------------------------------------ game state

    private fun hash(): Int {
        var h = (if (side == 1) 0 else 123456789) xor (cs * 40503)
        for (s in 0 until 64) {
            val p = b[s]
            if (p != 0) h = h xor ZOB[(p + 6) * 64 + s]
        }
        return h
    }

    private fun insufficient(): Boolean {
        var n = 0
        for (s in 0 until 64) {
            val q = abs(b[s])
            if (q == 1 || q == 4 || q == 5) return false
            if (q == 2 || q == 3) n++
        }
        return n < 2
    }

    /** Appends the current position to [h] and returns a result text, or "" if the game goes on. */
    fun status(h: MutableList<Int>): String {
        val hs = hash()
        h.add(hs)
        if (!hasLegal()) {
            return if (inCheck()) (if (side == 1) "Black" else "White") + " wins by checkmate" else "Stalemate"
        }
        if (hm >= 100 || insufficient()) return "Draw"
        var c = 0
        var i = h.size - 1
        while (i >= 0 && i >= h.size - hm - 1) {
            if (h[i] == hs) c++
            i -= 2
        }
        return if (c >= 3) "Draw by repetition" else ""
    }

    // ------------------------------------------------------------------ self-play and learning

    /** Plays one game between weight sets [wW] (white) and [wB] (black) into [out]. */
    fun play(wW: DoubleArray, wB: DoubleArray, eW: Double, eB: Double, out: GameBuf) {
        reset()
        Weights.buildValues(wW, vw)
        Weights.buildValues(wB, vb)
        var hn = 0
        var z = 0.0
        var mate = false
        var np = 0
        for (ply in 0 until MAXP) {
            val m = pick(if (side == 1) vw else vb, if (ply < 4) 0.6 else (if (side == 1) eW else eB))
            if (m < 0) {
                if (att(ks[kidx(side)], -side)) { z = (-side).toDouble(); mate = true }
                break
            }
            make(m)
            sp -= 7
            out.moves[np++] = m
            System.arraycopy(b, 0, out.boards, ply * 64, 64)
            val h = hash()
            hist[hn++] = h
            if (hm >= 100 || insufficient()) break
            var c = 0
            var i = hn - 1
            while (i >= 0 && i >= hn - hm - 1) {
                if (hist[i] == h) c++
                i -= 2
            }
            if (c >= 3) break
            if (ply == MAXP - 1) {
                var d = 0
                for (k in 0 until 64) {
                    val p = b[k]
                    if (p != 0) d += (if (p > 0) 1 else -1) * MATERIAL[abs(p)]
                }
                z = tanh(0.25 * d) * 0.5
            }
        }
        out.z = z; out.mate = mate; out.plies = np
    }

    private fun updateRow(w: DoubleArray, bs: IntArray, o: Int, e: Double) {
        for (i in 0 until 64) {
            val p = bs[o + i]
            if (p == 0) continue
            val a = if (p > 0) 1 else -1
            val q = a * p
            val k = (q - 1) * 32 + OW[if (a > 0) i else 64 + i]
            val d = e * a
            w[k] = Weights.clamp(w[k] + d)
            if (q < 6) {
                val j = MAT + q
                w[j] = Weights.clamp(w[j] + d)
            }
        }
    }

    /** TD(lambda): each position moves toward a blend of the next position's value and the result [z]. */
    fun trainTD(w: DoubleArray, bs: IntArray, n: Int, z: Double, lr: Double, lam: Double) {
        if (n == 0) return
        val v = tv
        for (t in 0 until n) v[t] = tanh(Weights.score(w, bs, t * 64))
        var target = z
        for (t in n - 1 downTo 0) {
            if (t < n - 1) target = (1 - lam) * v[t + 1] + lam * target
            val e = (target - v[t]) * (1 - v[t] * v[t]) * lr
            updateRow(w, bs, t * 64, e)
        }
    }

    /** Supervised update toward explicit per-position targets; returns the mean squared error. */
    fun trainSupervised(w: DoubleArray, bs: IntArray, n: Int, targets: DoubleArray, lr: Double): Double {
        var er = 0.0
        for (t in 0 until n) {
            val v = tanh(Weights.score(w, bs, t * 64))
            val x = targets[t] - v
            er += x * x
            updateRow(w, bs, t * 64, x * (1 - v * v) * lr)
        }
        return if (n > 0) er / n else 0.0
    }

    private companion object {
        val MATERIAL = intArrayOf(0, 1, 3, 3, 5, 9, 0)
    }
}
