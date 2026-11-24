package io.lampyr.glasses.input

import android.annotation.TargetApi
import android.companion.*
import android.content.Intent
import io.lampyr.glasses.service.GlassesService

@TargetApi(31)
@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
class ControllerCompanionService : CompanionDeviceService() {
    private fun ours(address: String?) = address != null && address.equals(getSharedPreferences("controller", MODE_PRIVATE).getString("associationAddress", null), true)
    override fun onDeviceAppeared(address: String) { appeared(address) }
    override fun onDeviceDisappeared(address: String) { disappeared(address) }
    @TargetApi(33)
    override fun onDeviceAppeared(associationInfo: AssociationInfo) { appeared(associationInfo.deviceMacAddress?.toString()) }
    @TargetApi(33)
    override fun onDeviceDisappeared(associationInfo: AssociationInfo) { disappeared(associationInfo.deviceMacAddress?.toString()) }
    private fun appeared(address: String?) {
        if (ours(address)) {
            io.lampyr.glasses.service.ServiceAccess.start(this)
            GlassesService.instance?.controller?.appeared()
        }
    }
    private fun disappeared(address: String?) { if (ours(address)) GlassesService.instance?.controller?.disconnected() }
}
