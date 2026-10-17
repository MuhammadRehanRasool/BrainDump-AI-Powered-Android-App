package dev.braindump.splitter.data.ai

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** User-facing model choices, persisted across launches. */
@Singleton
class AiSettings @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _usePreviewModel = MutableStateFlow(prefs.getBoolean(KEY_USE_PREVIEW, false))

    /** Try the AICore Developer Preview build of Gemini Nano before the stable one. */
    val usePreviewModel: StateFlow<Boolean> = _usePreviewModel.asStateFlow()

    fun setUsePreviewModel(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_USE_PREVIEW, enabled).apply()
        _usePreviewModel.value = enabled
    }

    private companion object {
        const val PREFS_NAME = "ai_settings"
        const val KEY_USE_PREVIEW = "use_preview_model"
    }
}
