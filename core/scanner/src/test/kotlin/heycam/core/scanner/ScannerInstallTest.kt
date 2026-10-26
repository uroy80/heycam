package heycam.core.scanner

import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Status
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate.InstallState
import com.google.common.truth.Truth.assertThat
import heycam.core.model.ModuleFailure
import heycam.core.model.ModuleState
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ScannerInstallTest {
    private fun update(state: Int, downloaded: Long? = null, total: Long? = null, error: Int = 0) =
        ModuleInstallStatusUpdate(1, state, downloaded, total, error)

    @Test
    fun progressUpdatesMapToModuleStates() {
        assertThat(ScannerInstall.stateOf(update(InstallState.STATE_DOWNLOADING, 3, 9))).isEqualTo(ModuleState.Downloading(3, 9))
        assertThat(ScannerInstall.stateOf(update(InstallState.STATE_INSTALLING))).isEqualTo(ModuleState.Installing)
        assertThat(ScannerInstall.stateOf(update(InstallState.STATE_COMPLETED))).isEqualTo(ModuleState.Ready)
        assertThat(ScannerInstall.stateOf(update(InstallState.STATE_DOWNLOAD_PAUSED)))
            .isEqualTo(ModuleState.Failed(ModuleFailure.NO_NETWORK, retryable = true))
        assertThat(ScannerInstall.stateOf(update(InstallState.STATE_FAILED, error = CommonStatusCodes.TIMEOUT)))
            .isEqualTo(ModuleState.Failed(ModuleFailure.NO_NETWORK, retryable = true))
        assertThat(ScannerInstall.stateOf(update(InstallState.STATE_UNKNOWN))).isNull()
        assertThat(ScannerInstall.failureOf(ApiException(Status(CommonStatusCodes.CANCELED))).reason).isEqualTo(ModuleFailure.CANCELLED)
    }
}
