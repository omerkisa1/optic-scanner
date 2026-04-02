package com.omrreader.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

@Composable
fun CreateExamScreen(onBack: () -> Unit, onNext: (Long) -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Create Exam Screen")
    }
}

@Composable
fun AnswerKeyScreen(examId: Long, onBack: () -> Unit, onFinish: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Answer Key Screen for Exam $examId")
    }
}

@Composable
fun ExamDetailScreen(examId: Long, onBack: () -> Unit, onScan: (Long) -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Exam Detail Screen for Exam $examId")
    }
}

@Composable
fun ScanScreen(examId: Long, onBack: () -> Unit, onScanSuccess: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Camera Scan Screen for Exam $examId")
    }
}

@Composable
fun ReviewScanScreen(onBack: () -> Unit, onConfirm: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Review Scan Result")
    }
}
