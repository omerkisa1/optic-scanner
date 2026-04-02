package com.omrreader.scoring

import com.omrreader.domain.model.ScoreResult
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

@Singleton
class ScoringEngine @Inject constructor() {

    fun initializeWeights(questionCount: Int, total: Double = 100.0): List<Double> {
        if (questionCount == 0) return emptyList()
        val weight = total / questionCount
        return List(questionCount) { weight }
    }

    fun recalculateWeights(
        questionCount: Int,
        lockedWeights: Map<Int, Double>,
        total: Double = 100.0
    ): List<Double>? {
        val lockedTotal = lockedWeights.values.sum()

        if (lockedTotal >= total && lockedWeights.size < questionCount) return null

        val remainingTotal = total - lockedTotal
        val remainingCount = questionCount - lockedWeights.size

        if (remainingCount <= 0) {
            return if (lockedTotal == total) {
                List(questionCount) { index -> lockedWeights[index] ?: 0.0 }
            } else null
        }

        val remainingWeight = remainingTotal / remainingCount
        val weights = MutableList(questionCount) { index ->
            lockedWeights[index] ?: remainingWeight
        }

        val currentTotal = lockedTotal + (remainingWeight * remainingCount)
        val diff = total - currentTotal

        val lastUnlockedIndex = weights.indices.lastOrNull { !lockedWeights.containsKey(it) }
        if (lastUnlockedIndex != null && diff != 0.0) {
            weights[lastUnlockedIndex] = weights[lastUnlockedIndex] + diff
        }

        return weights
    }

    fun calculateScore(
        studentAnswers: List<Int?>,
        correctAnswers: List<Int>,
        weights: List<Double>
    ): ScoreResult {
        var totalScore = 0.0
        var correct = 0
        var wrong = 0
        var empty = 0

        val maxIndices = minOf(studentAnswers.size, correctAnswers.size)
        
        for (i in 0 until maxIndices) {
            when {
                studentAnswers[i] == null -> empty++
                studentAnswers[i] == correctAnswers[i] -> {
                    correct++
                    totalScore += weights.getOrNull(i) ?: 0.0
                }
                else -> wrong++
            }
        }

        return ScoreResult(
            totalScore = (totalScore * 100).roundToInt() / 100.0,
            correctCount = correct,
            wrongCount = wrong,
            emptyCount = empty
        )
    }
}
