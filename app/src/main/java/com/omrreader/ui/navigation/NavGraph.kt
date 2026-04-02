package com.omrreader.ui.navigation

import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.omrreader.ui.screens.ExamDetailScreen
import com.omrreader.ui.screens.exam.AnswerKeyScreen
import com.omrreader.ui.screens.exam.CreateExamScreen
import com.omrreader.ui.screens.home.HomeScreen
import com.omrreader.ui.screens.scan.ReviewScreen
import com.omrreader.ui.screens.scan.ScanScreen
import com.omrreader.ui.screens.scan.ScanViewModel

@Composable
fun RootNavGraph() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = Screen.Home.route
    ) {
        composable(Screen.Home.route) {
            HomeScreen(
                onNavigateToCreate = { navController.navigate(Screen.CreateExam.route) },
                onNavigateToDetail = { examId -> navController.navigate(Screen.ExamDetail.createRoute(examId)) }
            )
        }

        composable(Screen.CreateExam.route) {
            CreateExamScreen(
                onBack = { navController.popBackStack() },
                onNext = { examId -> 
                    navController.navigate(Screen.AnswerKey.createRoute(examId)) {
                        popUpTo(Screen.Home.route)
                    }
                }
            )
        }

        composable(
            route = Screen.AnswerKey.route,
            arguments = listOf(navArgument("examId") { type = NavType.LongType })
        ) { backStackEntry ->
            val examId = backStackEntry.arguments?.getLong("examId") ?: 0L
            AnswerKeyScreen(
                examId = examId,
                onBack = { navController.popBackStack() },
                onFinish = {
                    navController.popBackStack(Screen.Home.route, inclusive = false)
                }
            )
        }

        composable(
            route = Screen.ExamDetail.route,
            arguments = listOf(navArgument("examId") { type = NavType.LongType })
        ) { backStackEntry ->
            val examId = backStackEntry.arguments?.getLong("examId") ?: 0L
            ExamDetailScreen(
                examId = examId,
                onBack = { navController.popBackStack() },
                onScan = { eId -> navController.navigate(Screen.Scan.createRoute(eId)) }
            )
        }

        composable(
            route = Screen.Scan.route,
            arguments = listOf(navArgument("examId") { type = NavType.LongType })
        ) { backStackEntry ->
            val examId = backStackEntry.arguments?.getLong("examId") ?: 0L
            ScanScreen(
                examId = examId,
                onBack = { navController.popBackStack() },
                onScanSuccess = { navController.navigate(Screen.ReviewScan.route) }
            )
        }

        composable(Screen.ReviewScan.route) {
            val scanEntry = navController.previousBackStackEntry
            if (scanEntry != null) {
                val scanViewModel: ScanViewModel = hiltViewModel(scanEntry)
                ReviewScreen(
                    onConfirmSaved = { navController.popBackStack() },
                    onRetake = { navController.popBackStack() },
                    onCancel = {
                        navController.popBackStack(Screen.Home.route, inclusive = false)
                    },
                    viewModel = scanViewModel
                )
            }
        }
    }
}
