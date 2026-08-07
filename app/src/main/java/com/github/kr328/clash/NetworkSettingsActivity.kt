package com.github.kr328.clash

import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.design.NetworkSettingsDesign
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.util.startClashService
import com.github.kr328.clash.util.stopClashService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext

class NetworkSettingsActivity : BaseActivity<NetworkSettingsDesign>() {
    override suspend fun main() {
        val service = ServiceStore(this)
        var appliedAccessControlMode = withContext(Dispatchers.IO) {
            service.accessControlMode
        }
        var restartingAccessControlMode = appliedAccessControlMode
        var restartingClash = false

        val design = NetworkSettingsDesign(
            this,
            uiStore,
            service,
            clashRunning,
        )

        setContentDesign(design)

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    when (it) {
                        Event.ClashStart -> if (restartingClash) {
                            appliedAccessControlMode = restartingAccessControlMode
                            restartingClash = false
                            design.setAccessControlModeEnabled(true)
                        } else {
                            recreate()
                        }
                        Event.ClashStop, Event.ServiceRecreated ->
                            if (!restartingClash) recreate()
                        Event.ActivityStart -> if (restartingClash && clashRunning) {
                            appliedAccessControlMode = restartingAccessControlMode
                            restartingClash = false
                            design.setAccessControlModeEnabled(true)
                        }
                        else -> Unit
                    }
                }
                design.requests.onReceive {
                    when (it) {
                        NetworkSettingsDesign.Request.StartAccessControlList ->
                            startActivity(AccessControlActivity::class.intent)
                        NetworkSettingsDesign.Request.AccessControlModeChanged -> {
                            val accessControlMode = withContext(Dispatchers.IO) {
                                service.accessControlMode
                            }

                            if (accessControlMode != appliedAccessControlMode) {
                                if (clashRunning && uiStore.enableVpn) {
                                    restartingClash = true
                                    restartingAccessControlMode = accessControlMode
                                    design.setAccessControlModeEnabled(false)

                                    withContext(Dispatchers.IO + NonCancellable) {
                                        stopClashService()
                                        while (clashRunning) {
                                            delay(200)
                                        }
                                        startClashService()
                                    }
                                } else {
                                    appliedAccessControlMode = accessControlMode
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
