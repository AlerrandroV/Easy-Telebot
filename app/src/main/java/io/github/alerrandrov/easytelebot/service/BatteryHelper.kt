package io.github.alerrandrov.easytelebot.service

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import io.github.alerrandrov.easytelebot.R

/**
 * Isenção de otimização de bateria + guia por fabricante.
 * Referência: https://dontkillmyapp.com
 */
object BatteryHelper {

    data class Guide(val brandName: String, val slug: String, val stepsRes: Int)

    fun isIgnoringOptimizations(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(context.packageName) == true

    /** Abre o pedido direto de isenção (permitido fora da Play Store). */
    @SuppressLint("BatteryLife")
    fun requestIgnoreOptimizationsIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${context.packageName}"))

    fun appDetailsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:${context.packageName}"))

    fun dontKillMyAppIntent(guide: Guide): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse("https://dontkillmyapp.com/${guide.slug}"))

    fun guideForThisPhone(): Guide {
        val m = Build.MANUFACTURER.lowercase()
        return when {
            "xiaomi" in m || "redmi" in m || "poco" in m -> Guide("Xiaomi", "xiaomi", R.array.guide_xiaomi)
            "samsung" in m -> Guide("Samsung", "samsung", R.array.guide_samsung)
            "huawei" in m || "honor" in m -> Guide("Huawei/Honor", "huawei", R.array.guide_huawei)
            "oneplus" in m -> Guide("OnePlus", "oneplus", R.array.guide_oneplus)
            "oppo" in m || "realme" in m -> Guide("Oppo/Realme", "oppo", R.array.guide_oppo)
            "vivo" in m -> Guide("Vivo", "vivo", R.array.guide_vivo)
            "motorola" in m || "lenovo" in m -> Guide("Motorola", "motorola", R.array.guide_generic)
            else -> Guide(Build.MANUFACTURER.replaceFirstChar { it.uppercase() }, "general", R.array.guide_generic)
        }
    }
}
