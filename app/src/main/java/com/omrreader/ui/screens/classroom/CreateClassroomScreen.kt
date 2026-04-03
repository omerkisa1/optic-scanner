package com.omrreader.ui.screens.classroom

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateClassroomScreen(
    onClassroomCreated: (Long) -> Unit,
    onBack: () -> Unit,
    viewModel: ClassroomViewModel = hiltViewModel()
) {
    var courseName by remember { mutableStateOf("") }
    var gradeLevel by remember { mutableStateOf("") }
    var section by remember { mutableStateOf("") }
    var educationType by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Yeni Sınıf Oluştur") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("Geri") }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            OutlinedTextField(
                value = courseName,
                onValueChange = { courseName = it },
                label = { Text("Ders Adı") },
                placeholder = { Text("Örn: Web Programlama") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = gradeLevel,
                onValueChange = { gradeLevel = it },
                label = { Text("Öğrenim Yılı") },
                placeholder = { Text("Örn: 3. Sınıf") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = educationType,
                onValueChange = { educationType = it },
                label = { Text("Öğrenim Türü") },
                placeholder = { Text("Örn: Gece Grubu") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = section,
                onValueChange = { section = it },
                label = { Text("Şube") },
                placeholder = { Text("Örn: A") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = {
                    viewModel.createClassroom(courseName, gradeLevel, section, educationType) { id ->
                        onClassroomCreated(id)
                    }
                },
                enabled = courseName.isNotBlank() && gradeLevel.isNotBlank() && section.isNotBlank() && educationType.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) {
                Text("Sınıfı Oluştur")
            }
        }
    }
}
