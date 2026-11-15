package app.jubal.keshev

import android.app.Application
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization

class KeshevApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        NewPipe.init(NewPipeDownloader(), Localization("he", "IL"), ContentCountry("IL"))
    }
}
