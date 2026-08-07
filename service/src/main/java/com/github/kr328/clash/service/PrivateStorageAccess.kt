package com.github.kr328.clash.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.DocumentsContract
import com.github.kr328.clash.common.constants.Authorities

object PrivateStorageAccess {
    fun isEnabled(context: Context): Boolean {
        val component = ComponentName(context, PrivateStorageProvider::class.java)

        return context.packageManager.getComponentEnabledSetting(component) ==
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        val component = ComponentName(context, PrivateStorageProvider::class.java)
        if (enabled) {
            if (isEnabled(context))
                return

            revokePermissions(context)
            context.packageManager.setComponentEnabledSetting(
                component,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP,
            )
        } else {
            try {
                if (isEnabled(context)) {
                    context.packageManager.setComponentEnabledSetting(
                        component,
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP,
                    )
                }
            } finally {
                revokePermissions(context)
            }
        }

        notifyRootsChanged(context)
    }

    private fun revokePermissions(context: Context) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION

        context.revokeUriPermission(
            Uri.parse("content://${Authorities.MT_DATA_FILES_PROVIDER}"),
            flags,
        )
    }

    private fun notifyRootsChanged(context: Context) {
        context.contentResolver.notifyChange(
            DocumentsContract.buildRootsUri(Authorities.MT_DATA_FILES_PROVIDER),
            null,
        )
    }
}
