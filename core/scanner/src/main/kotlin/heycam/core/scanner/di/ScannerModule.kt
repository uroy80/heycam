package heycam.core.scanner.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import heycam.core.scanner.GmsScannerLauncher
import heycam.core.scanner.ScannerLauncher

@Module
@InstallIn(SingletonComponent::class)
abstract class ScannerModule {
    @Binds
    abstract fun scannerLauncher(impl: GmsScannerLauncher): ScannerLauncher
}
