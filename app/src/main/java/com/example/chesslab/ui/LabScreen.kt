package com.example.chesslab.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.chesslab.train.GameKind

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LabScreen(vm: LabViewModel, padding: PaddingValues) {
    val ui by vm.ui.collectAsState()
    val stats by vm.stats.collectAsState()

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = padding.calculateTopPadding() + 12.dp,
            bottom = padding.calculateBottomPadding() + 16.dp
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            Text("Chess self-play lab", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Two engines start knowing only the rules and teach themselves by playing each other on every core.",
                style = MaterialTheme.typography.bodyMedium
            )
        }
        item {
            BoardView(ui.board, vm::onSquare, Modifier.widthIn(max = 520.dp))
        }
        item {
            Text(ui.caption, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth())
        }
        if (ui.promo.isNotEmpty()) {
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ui.promo.forEach { p ->
                        Button(onClick = { vm.choosePromotion(p.code) }) { Text(p.label) }
                    }
                }
            }
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FilledTonalButton(onClick = vm::toggleRun) { Text(if (ui.running) "Pause" else "Resume") }
                OutlinedButton(onClick = vm::toggleTurbo, enabled = !ui.playing) {
                    Text(if (ui.turbo) "Turbo: on" else "Turbo: off")
                }
                OutlinedButton(onClick = vm::resetLearning) { Text("Reset learning") }
            }
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(onClick = { vm.startPlay(true) }) { Text("Play as white") }
                Button(onClick = { vm.startPlay(false) }) { Text("Play as black") }
                if (ui.playing) OutlinedButton(onClick = vm::stopPlay) { Text("Back to watching") }
            }
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Engine strength:", style = MaterialTheme.typography.labelLarge)
                FilterChip(ui.level == 0, { vm.setLevel(0) }, { Text("Easy") })
                FilterChip(ui.level == 1, { vm.setLevel(1) }, { Text("Medium") })
                FilterChip(ui.level == 2, { vm.setLevel(2) }, { Text("Hard") })
            }
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(ui.kind == GameKind.LAST, { vm.selectKind(GameKind.LAST) }, { Text("Latest") })
                FilterChip(ui.kind == GameKind.BEST, { vm.selectKind(GameKind.BEST) }, { Text("Fastest mate") })
                FilterChip(ui.kind == GameKind.WORST, { vm.selectKind(GameKind.WORST) }, { Text("Longest game") })
            }
        }
        item {
            StatRow {
                StatCard("${stats.rate}", "games per second", Modifier.weight(1f))
                StatCard("%,d".format(stats.total), "games played", Modifier.weight(1f))
            }
        }
        item {
            StatRow {
                StatCard("${stats.matePercent}%", "games ending in mate", Modifier.weight(1f))
                StatCard("${stats.winsA} : ${stats.winsB}", "wins A : B", Modifier.weight(1f))
            }
        }
        item {
            Text("What the engines currently think pieces are worth (pawn = 1)", style = MaterialTheme.typography.labelLarge)
            Text("A: ${stats.valuesA}", style = MaterialTheme.typography.bodyMedium)
            Text("B: ${stats.valuesB}", style = MaterialTheme.typography.bodyMedium)
        }
    }
}
