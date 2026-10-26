package heycam.core.ocr.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import heycam.core.ocr.LocaleScriptResolver
import heycam.core.ocr.MlKitOcrEngine
import heycam.core.ocr.OcrEngine
import heycam.core.ocr.ScriptResolver

@Module
@InstallIn(SingletonComponent::class)
abstract class OcrModule {
    @Binds
    abstract fun ocrEngine(impl: MlKitOcrEngine): OcrEngine

    @Binds
    abstract fun scriptResolver(impl: LocaleScriptResolver): ScriptResolver
}
