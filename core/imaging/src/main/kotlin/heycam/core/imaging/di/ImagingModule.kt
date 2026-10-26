package heycam.core.imaging.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import heycam.core.imaging.BitmapPageRenderer
import heycam.core.imaging.DecodingImageImporter
import heycam.core.imaging.DocumentPixelFilters
import heycam.core.imaging.ImageImporter
import heycam.core.imaging.PageRenderer
import heycam.core.imaging.PixelFilters

@Module
@InstallIn(SingletonComponent::class)
internal abstract class ImagingModule {
    @Binds abstract fun importer(impl: DecodingImageImporter): ImageImporter
    @Binds abstract fun renderer(impl: BitmapPageRenderer): PageRenderer
    @Binds abstract fun filters(impl: DocumentPixelFilters): PixelFilters
}
