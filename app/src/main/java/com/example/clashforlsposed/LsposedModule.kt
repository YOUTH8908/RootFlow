package com.example.clashforlsposed

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/** libxposed API 102 entry point for scoped target processes. */
class LsposedModule : XposedModule() {
    override fun onPackageReady(param: PackageReadyParam) {
        log(4, "ClashForLSPosed", "Module loaded for ${param.packageName}")
    }
}
