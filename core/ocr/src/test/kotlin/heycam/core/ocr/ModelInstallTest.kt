package heycam.core.ocr

import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.OptionalModuleApi
import com.google.android.gms.common.api.Status
import com.google.android.gms.common.moduleinstall.ModuleInstallClient
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.common.moduleinstall.ModuleInstallResponse
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate.InstallState
import com.google.android.gms.tasks.Tasks
import com.google.common.truth.Truth.assertThat
import heycam.core.model.ModuleFailure
import heycam.core.model.ModuleState
import java.lang.reflect.Proxy
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ModelInstallTest {
    private fun update(state: Int, downloaded: Long? = null, total: Long? = null, error: Int = 0) =
        ModuleInstallStatusUpdate(1, state, downloaded, total, error)

    @Test
    fun progressUpdatesMapToModuleStates() {
        assertThat(ModelInstall.stateOf(update(InstallState.STATE_PENDING))).isEqualTo(ModuleState.Downloading(0, null))
        assertThat(ModelInstall.stateOf(update(InstallState.STATE_DOWNLOADING, 500, 2_000)))
            .isEqualTo(ModuleState.Downloading(500, 2_000))
        // Play services leaves out progress it cannot size.
        assertThat(ModelInstall.stateOf(update(InstallState.STATE_DOWNLOADING, 500, 0))).isEqualTo(ModuleState.Downloading(0, null))
        assertThat(ModelInstall.stateOf(update(InstallState.STATE_INSTALLING))).isEqualTo(ModuleState.Installing)
        assertThat(ModelInstall.stateOf(update(InstallState.STATE_COMPLETED))).isEqualTo(ModuleState.Ready)
        assertThat(ModelInstall.stateOf(update(InstallState.STATE_CANCELED)))
            .isEqualTo(ModuleState.Failed(ModuleFailure.CANCELLED, retryable = true))
        assertThat(ModelInstall.stateOf(update(InstallState.STATE_DOWNLOAD_PAUSED)))
            .isEqualTo(ModuleState.Failed(ModuleFailure.NO_NETWORK, retryable = true))
        assertThat(ModelInstall.stateOf(update(InstallState.STATE_FAILED, error = CommonStatusCodes.NETWORK_ERROR)))
            .isEqualTo(ModuleState.Failed(ModuleFailure.NO_NETWORK, retryable = true))
        assertThat(ModelInstall.stateOf(update(InstallState.STATE_FAILED, error = 46003)))
            .isEqualTo(ModuleState.Failed(ModuleFailure.UNKNOWN, retryable = true))
        assertThat(ModelInstall.stateOf(update(InstallState.STATE_UNKNOWN))).isNull()
    }

    @Test
    fun apiErrorsMapToFailures() {
        assertThat(ModelInstall.failureOf(ApiException(Status(CommonStatusCodes.NETWORK_ERROR))).reason)
            .isEqualTo(ModuleFailure.NO_NETWORK)
        assertThat(ModelInstall.failureOf(IllegalStateException()).reason).isEqualTo(ModuleFailure.UNKNOWN)
    }

    @Test
    fun installStreamsProgressAndEndsWhenComplete() = runBlocking<Unit> {
        val client = client { request ->
            val listener = request.listener!!
            listener.onInstallStatusUpdated(update(InstallState.STATE_PENDING))
            listener.onInstallStatusUpdated(update(InstallState.STATE_DOWNLOADING, 1_000, 2_000))
            listener.onInstallStatusUpdated(update(InstallState.STATE_INSTALLING))
            listener.onInstallStatusUpdated(update(InstallState.STATE_COMPLETED))
            Tasks.forResult(ModuleInstallResponse(1, false))
        }
        assertThat(ModelInstall.install(client, listOf(api())).toList()).containsExactly(
            ModuleState.Downloading(0, null),
            ModuleState.Downloading(1_000, 2_000),
            ModuleState.Installing,
            ModuleState.Ready,
        ).inOrder()
    }

    @Test
    fun alreadyInstalledIsReadyAtOnce() = runBlocking<Unit> {
        val client = client { Tasks.forResult(ModuleInstallResponse(0, true)) }
        assertThat(ModelInstall.install(client, listOf(api())).toList()).containsExactly(ModuleState.Ready)
    }

    @Test
    fun aFailedRequestEndsWithFailed() = runBlocking<Unit> {
        val client = client { Tasks.forException(ApiException(Status(CommonStatusCodes.NETWORK_ERROR))) }
        assertThat(ModelInstall.install(client, listOf(api())).toList())
            .containsExactly(ModuleState.Failed(ModuleFailure.NO_NETWORK, retryable = true))
    }

    private fun api(): OptionalModuleApi = OptionalModuleApi { emptyArray() }

    private fun client(install: (ModuleInstallRequest) -> com.google.android.gms.tasks.Task<ModuleInstallResponse>): ModuleInstallClient =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(ModuleInstallClient::class.java)) { _, method, args ->
            when (method.name) {
                "installModules" -> install(args[0] as ModuleInstallRequest)
                "unregisterListener" -> Tasks.forResult(true)
                else -> throw UnsupportedOperationException(method.name)
            }
        } as ModuleInstallClient
}
