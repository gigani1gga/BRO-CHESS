package com.example.chesslab

import com.example.chesslab.engine.Engine
import com.example.chesslab.engine.GameBuf
import com.example.chesslab.engine.Tables.MAXP
import com.example.chesslab.engine.Weights
import com.example.chesslab.train.SelfPlayWorker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineTest {

    private fun perftCheck(fen: String, expected: LongArray) {
        val e = Engine(1)
        for (d in expected.indices) {
            e.setFen(fen)
            assertEquals("depth ${d + 1} of $fen", expected[d], e.perft(d + 1))
        }
    }

    @Test fun perftStartPosition() =
        perftCheck("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq -", longArrayOf(20, 400, 8902, 197281))

    @Test fun perftKiwipete() =
        perftCheck("r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq -", longArrayOf(48, 2039, 97862))

    @Test fun perftEndgameEnPassant() =
        perftCheck("8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - -", longArrayOf(14, 191, 2812, 43238))

    @Test fun perftPromotionsAndCastling() {
        perftCheck("r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq -", longArrayOf(6, 264, 9467))
        perftCheck("rnbq1k1r/pp1Pbppp/2p5/8/2B5/8/PPP1NnPP/RNBQK2R w KQ -", longArrayOf(44, 1486, 62379))
    }

    @Test fun fenRoundTrip() {
        val e = Engine(1)
        e.reset()
        assertEquals("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1", e.fen())
    }

    @Test fun detectsFoolsMate() {
        val e = Engine(1)
        e.setFen("rnb1kbnr/pppp1ppp/8/4p3/6Pq/5P2/PPPPP2P/RNBQKBNR w KQkq -")
        assertEquals("Black wins by checkmate", e.status(ArrayList()))
    }

    @Test fun selfPlayGameIsWellFormed() {
        val e = Engine(7)
        val w = Weights.random()
        val g = GameBuf()
        e.play(w, w, 0.1, 0.1, g)
        assertTrue(g.plies in 1..MAXP)
    }

    @Test fun workerTrainsWithinBudget() {
        val worker = SelfPlayWorker(3)
        val a = Weights.random()
        val b = Weights.random()
        val before = a.copyOf()
        val r = worker.runBatch(a, b, 300, 1.0)
        assertTrue(r.games > 0)
        assertTrue(!a.contentEquals(before))
        assertTrue(a.all { it.isFinite() && it in -3.0..3.0 })
    }
}
