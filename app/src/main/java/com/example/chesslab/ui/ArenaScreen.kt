package com.example.chesslab.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ArenaScreen(vm: ArenaViewModel, padding: PaddingValues) {
    val ui by vm.ui.collectAsState()
    val s = ui.stats

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = padding.calculateTopPadding() + 12.dp,
            bottom = padding.calculateBottomPadding() + 16.dp
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Stockfish arena", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Our evaluation plays Stockfish and learns from its judgement. Stockfish gets stronger as ours improves.",
                style = MaterialTheme.typography.bodyMedium
            )
        }
        if (!ui.available) {
            item {
                Text(
                    "No Stockfish binary found. Place a native Android Stockfish build named libstockfish.so in " +
                        "app/src/main/jniLibs/<abi>/ (arm64-v8a, armeabi-v7a, x86_64) and rebuild.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
        if (s.note.isNotEmpty()) {
            item { Text(s.note, color = MaterialTheme.colorScheme.error) }
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = vm::toggle, enabled = ui.available) { Text(if (ui.running) "Stop" else "Start") }
                OutlinedButton(onClick = vm::reset) { Text("Reset learning") }
            }
        }
        item {
            StatRow {
                StatCard("${s.games}", "games vs Stockfish", Modifier.weight(1f))
                StatCard("${s.wins} / ${s.draws} / ${s.losses}", "our wins / draws / losses", Modifier.weight(1f))
            }
        }
        item {
            StatRow {
                StatCard("${s.level}", "Stockfish skill level (0–20)", Modifier.weight(1f))
                StatCard(String.format(Locale.US, "%.2f", s.recent), "our score, last 20 games (−1 to 1)", Modifier.weight(1f))
            }
        }
        item {
            StatRow {
                StatCard(String.format(Locale.US, "%.3f", s.error), "eval error vs Stockfish (lower is better)", Modifier.weight(1f))
                StatCard("%,d".format(s.positions), "positions learned from", Modifier.weight(1f))
            }
        }
    }
}
