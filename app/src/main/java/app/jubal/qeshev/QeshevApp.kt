package app.jubal.qeshev

import android.app.Application
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization

class QeshevApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Settings.applyTheme(this)
        Store.init(this)
        NewPipe.init(NewPipeDownloader(), Localization("he", "IL"), ContentCountry("IL"))
    }
}
