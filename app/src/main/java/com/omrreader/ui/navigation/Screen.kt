package com.omrreader.ui.navigation

sealed class Screen(val route: String) {
    object Home : Screen("home")
    object CreateExam : Screen("create_exam")
    object AnswerKey : Screen("answer_key/{examId}") {
        fun createRoute(examId: Long) = "answer_key/$examId"
    }
    object Scan : Screen("scan/{examId}") {
        fun createRoute(examId: Long) = "scan/$examId"
    }
    object Review : Screen("review")
    object ExamDetail : Screen("exam_detail/{examId}") {
        fun createRoute(examId: Long) = "exam_detail/$examId"
    }
    object ResultDetail : Screen("result_detail/{resultId}") {
        fun createRoute(resultId: Long) = "result_detail/$resultId"
    }
    object Export : Screen("export/{examId}") {
        fun createRoute(examId: Long) = "export/$examId"
    }
}
