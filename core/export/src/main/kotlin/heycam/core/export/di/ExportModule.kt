package heycam.core.export.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import heycam.core.export.AndroidPrintService
import heycam.core.export.DefaultExportService
import heycam.core.export.ExportService
import heycam.core.export.PrintService

@Module
@InstallIn(SingletonComponent::class)
abstract class ExportModule {
    @Binds
    abstract fun exportService(impl: DefaultExportService): ExportService

    @Binds
    abstract fun printService(impl: AndroidPrintService): PrintService
}
