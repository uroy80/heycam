package heycam.core.security

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** MVP bindings (PLAN 4.10). Tests swap this module out with @TestInstallIn and FakeAppLock from :core:testing. */
@Module
@InstallIn(SingletonComponent::class)
abstract class SecurityModule {
    @Binds
    abstract fun appLock(impl: DisabledAppLock): AppLock

    @Binds
    abstract fun secureWindowPolicy(impl: NeverSecureWindowPolicy): SecureWindowPolicy
}
