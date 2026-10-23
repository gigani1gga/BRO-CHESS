package com.example.chesslab.engine

/**
 * Immutable lookup tables shared by every [Engine] instance (safe across threads).
 *
 * Board: index 0 = a8 ... 63 = h1. White pieces are positive, black negative:
 * 1 pawn, 2 knight, 3 bishop, 4 rook, 5 queen, 6 king.
 *
 * The tables are built by plain functions with simple loops (no nested inline lambdas): the nested
 * form made the Kotlin compiler emit an invalid stack map that the Android dexer rejects.
 */
object Tables {
    const val NF = 198      // learned parameters: 6 piece tables of 32 + material values
    const val MAXP = 100    // plies per self-play game
    const val MAT = 191     // material value of piece q lives at index MAT + q

    private val DR = intArrayOf(-1, 1, 0, 0, -1, -1, 1, 1)
    private val DF = intArrayOf(0, 0, -1, 1, -1, 1, -1, 1)

    /** Rays per square; directions 0..3 are rook-like, 4..7 bishop-like. */
    @JvmField
    val RY: Array<Array<IntArray>> = buildRays()

    /** King destinations (first square of every ray). */
    @JvmField
    val KI: Array<IntArray> = buildKing()

    @JvmField
    val KN: Array<IntArray> = buildKnight()

    /** Maps (side, square) to the left/right-folded 8x4 table slot. [0,64) white view, [64,128) black view. */
    @JvmField
    val OW: IntArray = buildOw()

    /** Castling-rights mask applied when a piece moves from or to a square. */
    @JvmField
    val CM: IntArray = buildCm()

    @JvmField
    val ZOB: IntArray = buildZob()

    /** Trigger masks for "could this move give a direct check": indexed by piece type. */
    @JvmField
    val TM: IntArray = intArrayOf(0, 1, 2, 4, 8, 12, 0)

    private fun buildRays(): Array<Array<IntArray>> {
        val squares = ArrayList<Array<IntArray>>(64)
        for (sq in 0 until 64) {
            val dirs = ArrayList<IntArray>(8)
            for (d in 0 until 8) {
                val tmp = IntArray(8)
                var n = 0
                var r = (sq shr 3) + DR[d]
                var f = (sq and 7) + DF[d]
                while (r in 0..7 && f in 0..7) {
                    tmp[n] = r * 8 + f
                    n++
                    r += DR[d]
                    f += DF[d]
                }
                dirs.add(tmp.copyOf(n))
            }
            squares.add(dirs.toTypedArray())
        }
        return squares.toTypedArray()
    }

    private fun buildKing(): Array<IntArray> {
        val res = ArrayList<IntArray>(64)
        for (sq in 0 until 64) {
            val tmp = IntArray(8)
            var n = 0
            for (d in 0 until 8) {
                val ray = RY[sq][d]
                if (ray.isNotEmpty()) {
                    tmp[n] = ray[0]
                    n++
                }
            }
            res.add(tmp.copyOf(n))
        }
        return res.toTypedArray()
    }

    private fun buildKnight(): Array<IntArray> {
        val jumps = intArrayOf(-2, -1, -2, 1, -1, -2, -1, 2, 1, -2, 1, 2, 2, -1, 2, 1)
        val res = ArrayList<IntArray>(64)
        for (sq in 0 until 64) {
            val r = sq shr 3
            val f = sq and 7
            val tmp = IntArray(8)
            var n = 0
            var i = 0
            while (i < jumps.size) {
                val rr = r + jumps[i]
                val ff = f + jumps[i + 1]
                if (rr in 0..7 && ff in 0..7) {
                    tmp[n] = rr * 8 + ff
                    n++
                }
                i += 2
            }
            res.add(tmp.copyOf(n))
        }
        return res.toTypedArray()
    }

    private fun buildOw(): IntArray {
        val ow = IntArray(128)
        for (s in 0 until 64) {
            val r = s shr 3
            val f = s and 7
            val fo = if (f > 3) 7 - f else f
            ow[s] = r * 4 + fo
            ow[64 + s] = (7 - r) * 4 + fo
        }
        return ow
    }

    private fun buildCm(): IntArray {
        val cm = IntArray(64)
        for (i in 0 until 64) cm[i] = 15
        cm[60] = 12; cm[63] = 14; cm[56] = 13
        cm[4] = 3; cm[7] = 11; cm[0] = 7
        return cm
    }

    private fun buildZob(): IntArray {
        val z = IntArray(832)
        val rnd = java.util.Random(0x5DEECE66DL)
        for (i in 0 until 832) z[i] = rnd.nextInt()
        return z
    }
}
