package com.omrreader.ui.screens.scan

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omrreader.data.repository.ExamRepository
import com.omrreader.domain.model.ProcessResult
import com.omrreader.domain.usecase.ProcessOMRUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class ScanState {
    object Ready : ScanState()
    object Processing : ScanState()
    data class ReviewReady(val result: ProcessResult.Success) : ScanState()
    data class Error(val message: String) : ScanState()
}

@HiltViewModel
class ScanViewModel @Inject constructor(
    private val processOMRUseCase: ProcessOMRUseCase,
    private val examRepository: ExamRepository
) : ViewModel() {

    private val _scanState = MutableStateFlow<ScanState>(ScanState.Ready)
    val scanState: StateFlow<ScanState> = _scanState.asStateFlow()

    private val _activeExamId = MutableStateFlow<Long?>(null)
    val activeExamId: StateFlow<Long?> = _activeExamId.asStateFlow()

    fun setActiveExam(examId: Long) {
        _activeExamId.value = examId
    }

    fun processImage(bitmap: Bitmap) {
        viewModelScope.launch(Dispatchers.Default) {
            _scanState.value = ScanState.Processing
            val result = processOMRUseCase.process(bitmap)
            _scanState.value = when (result) {
                is ProcessResult.Success -> ScanState.ReviewReady(result)
                is ProcessResult.Error -> ScanState.Error(result.message)
            }
        }
    }

    fun consumeReviewNavigation() {
        if (_scanState.value is ScanState.ReviewReady) {
            _scanState.value = ScanState.Ready
        }
    }

    fun clearError() {
        if (_scanState.value is ScanState.Error) {
            _scanState.value = ScanState.Ready
        }
    }
}
