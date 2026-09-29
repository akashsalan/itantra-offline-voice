package org.itantra.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel

class TalkViewModel(application: Application) : AndroidViewModel(application) {
    val runtime = (application as ItantraApplication).runtime
}
