package app.hfd

import android.app.Application

class HfdApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
    }
}
