package heycam.core.domain.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import heycam.core.domain.ConnectivityNetworkMonitor
import heycam.core.domain.DefaultScanImporter
import heycam.core.domain.DefaultTitle
import heycam.core.domain.DeviceLocales
import heycam.core.domain.LocalizedDefaultTitle
import heycam.core.domain.NetworkMonitor
import heycam.core.domain.PageProcessor
import heycam.core.domain.QueuedPageProcessor
import heycam.core.domain.ScanImporter
import heycam.core.domain.SystemDeviceLocales

@Module
@InstallIn(SingletonComponent::class)
abstract class DomainModule {
    @Binds
    abstract fun pageProcessor(impl: QueuedPageProcessor): PageProcessor

    @Binds
    abstract fun scanImporter(impl: DefaultScanImporter): ScanImporter

    @Binds
    abstract fun defaultTitle(impl: LocalizedDefaultTitle): DefaultTitle

    @Binds
    abstract fun deviceLocales(impl: SystemDeviceLocales): DeviceLocales

    @Binds
    abstract fun networkMonitor(impl: ConnectivityNetworkMonitor): NetworkMonitor
}
