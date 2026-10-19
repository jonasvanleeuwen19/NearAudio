package app.nearaudio

import android.app.Application
import app.nearaudio.session.Engine

class NearAudioApp : Application() {
    val engine: Engine by lazy { Engine(this) }
}
