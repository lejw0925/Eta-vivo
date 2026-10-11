package io.github.mangi.eta.agent.display

import android.app.AppOpsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.model.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Owns the system PiP preview for the execution service; creates no overlay windows. */
internal class VirtualScreenPreviewHost(private val context: Context) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var settings = Settings()
    private var state = VirtualScreenViewerState()
    private val pip = VirtualScreenPipPreviewController(context, scope) { scope.launch { render() } }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { render() }
    }
    private val appOps = context.getSystemService(AppOpsManager::class.java)
    private val permissionListener = AppOpsManager.OnOpChangedListener { _, _ -> scope.launch { render() } }

    init {
        SettingsDataStore.init(context)
        appOps.startWatchingMode(AppOpsManager.OPSTR_PICTURE_IN_PICTURE, context.packageName, permissionListener)
        context.registerReceiver(receiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_CONFIGURATION_CHANGED)
        }, Context.RECEIVER_NOT_EXPORTED)
        scope.launch {
            combine(SettingsDataStore.settingsFlow(), VirtualScreenSession.state) { options, display -> options to display }
                .collect { (options, display) ->
                    if (options.virtualScreenPreviewEnabled != settings.virtualScreenPreviewEnabled) pip.reset()
                    settings = options
                    state = display
                    render()
                }
        }
    }

    private fun render() {
        pip.updateTarget(state)
        val visible = settings.virtualScreenEnabled && settings.virtualScreenPreviewEnabled &&
            state.isAgentControlling && state.activeRunId != null && !state.fullViewerVisible &&
            VirtualScreenPipPreviewController.allowed(context) && VirtualScreenPipPreviewController.screenAvailable(context)
        if (!visible) {
            pip.hide()
        } else if (pip.canLaunch) {
            pip.launch()
        }
    }

    override fun close() {
        pip.close()
        appOps.stopWatchingMode(permissionListener)
        scope.cancel()
        runCatching { context.unregisterReceiver(receiver) }
    }
}
