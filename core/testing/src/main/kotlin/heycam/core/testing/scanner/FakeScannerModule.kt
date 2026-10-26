package heycam.core.testing.scanner

import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import heycam.core.common.StagingArea
import heycam.core.scanner.ScannerLauncher
import heycam.core.scanner.di.ScannerModule
import javax.inject.Singleton

/**
 * In every Hilt test that has :core:testing, the scanner is [FakeScannerLauncher] with the English/Hindi sample:
 * Google's scanner screens cannot be driven by tests (mlkit-spike). Tests reach it by injecting ScannerLauncher and
 * casting.
 */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [ScannerModule::class])
object FakeScannerModule {
    @Provides
    @Singleton
    fun scannerLauncher(staging: StagingArea): ScannerLauncher = FakeScannerLauncher(staging)
}
