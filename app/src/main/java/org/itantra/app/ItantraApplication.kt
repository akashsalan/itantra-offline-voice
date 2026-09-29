package org.itantra.app

import android.app.Application
class ItantraApplication : Application() {
    val runtime: AppRuntime by lazy { AppRuntime(this) }
}
