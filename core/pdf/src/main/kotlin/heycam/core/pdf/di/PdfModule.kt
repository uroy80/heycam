package heycam.core.pdf.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import heycam.core.pdf.PdfBoxPdfService
import heycam.core.pdf.PdfService

@Module
@InstallIn(SingletonComponent::class)
abstract class PdfModule {
    @Binds
    abstract fun pdfService(impl: PdfBoxPdfService): PdfService
}
