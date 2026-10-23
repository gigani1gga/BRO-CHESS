package com.example.chesslab.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.example.chesslab.ui.theme.ChessLabTheme

@Composable
fun App(lab: LabViewModel, arena: ArenaViewModel) {
    ChessLabTheme {
        var tab by rememberSaveable { mutableStateOf(0) }
        Surface(Modifier.fillMaxSize()) {
            Scaffold(
                bottomBar = {
                    NavigationBar {
                        NavigationBarItem(tab == 0, { tab = 0 }, { Text("\u265E") }, label = { Text("Lab") })
                        NavigationBarItem(tab == 1, { tab = 1 }, { Text("\u265A") }, label = { Text("Arena") })
                    }
                }
            ) { padding ->
                if (tab == 0) LabScreen(lab, padding) else ArenaScreen(arena, padding)
            }
        }
    }
}
