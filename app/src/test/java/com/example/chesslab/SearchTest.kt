package com.example.chesslab

import com.example.chesslab.engine.Engine
import com.example.chesslab.engine.Searcher
import com.example.chesslab.engine.Weights
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchTest {

    private fun sq(name: String): Int = (name[0] - 'a') + (8 - (name[1] - '0')) * 8
    private fun mv(from: String, to: String): Int = sq(from) or (sq(to) shl 6)

    @Test fun findsMateInOne() {
        val e = Engine(1)
        e.setFen("6k1/5ppp/8/8/8/8/5PPP/R5K1 w - -")
        val r = Searcher(e, 16).best(500, 6)
        assertEquals(mv("a1", "a8"), r.move)
        assertTrue(r.score > 29000)
    }

    @Test fun winsHangingQueen() {
        val e = Engine(1)
        e.setFen("4k3/8/8/3q4/8/8/8/3QK3 w - -")
        val r = Searcher(e, 16).best(500, 5)
        assertEquals(mv("d1", "d5"), r.move)
    }

    @Test fun doesNotHangItsQueen() {
        // Qxd5 would lose the queen to the pawn on c6; any sane search avoids it.
        val e = Engine(1)
        e.setFen("4k3/8/2p5/3p4/8/8/8/3QK3 w - -")
        val r = Searcher(e, 16).best(400, 6)
        assertTrue(r.move != mv("d1", "d5"))
    }

    @Test fun returnsLegalMoveFromStartPosition() {
        val e = Engine(1)
        e.reset()
        val r = Searcher(e, 16).best(200, 5)
        assertTrue(e.legalMoves().contains(r.move))
    }

    @Test fun searchLeavesPositionUntouched() {
        val e = Engine(1)
        e.reset()
        val before = e.fen()
        Searcher(e, 16).best(150, 4)
        assertEquals(before, e.fen())
    }

    @Test fun reportsNoMoveWhenCheckmated() {
        val e = Engine(1)
        e.setFen("rnb1kbnr/pppp1ppp/8/4p3/6Pq/5P2/PPPPP2P/RNBQKBNR w KQkq -")
        val r = Searcher(e, 16).best(100, 3)
        assertEquals(-1, r.move)
    }

    @Test fun worksWithLearnedWeights() {
        val e = Engine(1)
        e.reset()
        val s = Searcher(e, 16)
        val w = Weights.random()
        for (q in 1..5) w[191 + q] = 0.2 * q   // pretend the engine has learned sensible material values
        s.setWeights(w)
        val r = s.best(150, 4)
        assertTrue(e.legalMoves().contains(r.move))
    }

    @Test fun copyFromClonesPosition() {
        val a = Engine(1)
        a.setFen("r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq -")
        val b = Engine(2)
        b.copyFrom(a)
        assertEquals(a.fen(), b.fen())
        assertEquals(a.perft(2), b.perft(2))
    }
}
