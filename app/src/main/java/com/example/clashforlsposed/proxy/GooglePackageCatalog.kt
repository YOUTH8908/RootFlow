package com.example.clashforlsposed.proxy

import android.content.Context
import android.content.pm.ApplicationInfo

object GooglePackageCatalog {
    fun uids(context: Context): List<Int> {
        return context.packageManager.getInstalledApplications(0)
            .asSequence()
            .filter { isGooglePackage(it.packageName) }
            .filter {
                it.flags and ApplicationInfo.FLAG_SYSTEM != 0 ||
                    it.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
            }
            .map { it.uid }
            .filter { it >= 10_000 }
            .distinct()
            .sorted()
            .toList()
    }

    fun isGooglePackage(packageName: String): Boolean {
        return packageName.startsWith("com.google.") ||
            packageName.startsWith("com.google.android.") ||
            packageName == "com.android.vending"
    }
}
