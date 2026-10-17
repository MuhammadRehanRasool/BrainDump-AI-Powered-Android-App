package dev.braindump.splitter.ui.capture

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.braindump.splitter.di.ApplicationScope
import dev.braindump.splitter.domain.repository.IdeaRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CaptureViewModel @Inject constructor(
    private val repository: IdeaRepository,
    @ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {

    /** Fire-and-forget: the sheet closes immediately while the write finishes in the app scope. */
    fun submit(text: String) {
        if (text.isBlank()) return
        appScope.launch { repository.capture(text) }
    }
}
