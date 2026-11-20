package com.feerlessleadr.dockge

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * The launcher icon the user picked. Each icon is an `activity-alias` in the manifest that carries the launcher
 * intent filter; exactly one is enabled. Switching enables the new alias first and then disables the old one, so the
 * app never has no launcher entry.
 */
enum class AppIcon(val alias: String, val title: String, val detail: String) {
    Blue(".IconBlue", "Blue", "White layers on the app blue."),
    Light(".IconLight", "Light", "Dark layers on a pale blue tile.");

    companion object {
        fun current(context: Context): AppIcon =
            if (state(context, Light) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) Light else Blue

        fun select(context: Context, icon: AppIcon) {
            if (current(context) == icon) return
            val other = entries.first { it != icon }
            set(context, icon, PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
            set(context, other, PackageManager.COMPONENT_ENABLED_STATE_DISABLED)
        }

        private fun component(context: Context, icon: AppIcon) = ComponentName(context.packageName, "com.feerlessleadr.dockge${icon.alias}")

        private fun state(context: Context, icon: AppIcon) = context.packageManager.getComponentEnabledSetting(component(context, icon))

        private fun set(context: Context, icon: AppIcon, state: Int) {
            context.packageManager.setComponentEnabledSetting(component(context, icon), state, PackageManager.DONT_KILL_APP)
        }
    }
}
