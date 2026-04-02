package com.omrreader.ui.screens.scan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(
    onBack: () -> Unit,
    onConfirm: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Tarama Önizleme") })
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Tarama tamamlandı. Sonuçlar bu ekranda doğrulanacak.",
                style = MaterialTheme.typography.bodyLarge
            )
            Button(
                onClick = onConfirm,
                modifier = Modifier.padding(top = 16.dp)
            ) {
                Text("Onayla")
            }
            Button(
                onClick = onBack,
                modifier = Modifier.padding(top = 12.dp)
            ) {
                Text("Tekrar Çek")
            }
        }
    }
}
