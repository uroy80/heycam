package heycam.core.domain

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import heycam.core.model.ModuleState
import heycam.core.model.OcrScript
import heycam.core.ocr.OcrEngine
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.transformWhile

/**
 * Settings → "Download all languages": fetches Google Play services text-recognition models before they are needed,
 * for example before going offline. Features may not depend on :core:ocr, so :core:domain delegates to
 * OcrEngine.ensureModels. The flow ends after a terminal state (Ready, Failed or Unsupported).
 */
fun interface TextModels {
    fun download(scripts: Set<OcrScript>): Flow<ModuleState>
}

internal class OcrTextModels @Inject constructor(private val engine: OcrEngine) : TextModels {
    override fun download(scripts: Set<OcrScript>): Flow<ModuleState> =
        engine.ensureModels(scripts).transformWhile { state ->
            emit(state)
            !state.isTerminal
        }
}

internal val ModuleState.isTerminal: Boolean
    get() = this is ModuleState.Ready || this is ModuleState.Failed || this is ModuleState.Unsupported

@Module
@InstallIn(SingletonComponent::class)
internal abstract class TextModelsModule {
    @Binds
    abstract fun textModels(impl: OcrTextModels): TextModels
}
