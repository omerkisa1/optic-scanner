package com.omrreader.ui.navigation

sealed class Screen(val route: String) {
    object Home : Screen("home")
    object CreateExam : Screen("create_exam")
    object AnswerKey : Screen("answer_key/{examId}") {
        fun createRoute(examId: Long) = "answer_key/$examId"
    }
    object ExamDetail : Screen("exam_detail/{examId}") {
        fun createRoute(examId: Long) = "exam_detail/$examId"
    }
    object Scan : Screen("scan/{examId}") {
        fun createRoute(examId: Long) = "scan/$examId"
    }
    object ReviewScan : Screen("review_scan")
    // Note: We might pass cached scan result through ViewModel
}
