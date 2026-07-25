package __PKG__;

import android.app.Application;
import android.content.Context;
import android.util.Log;

/**
 * Exists purely to load {@link Cfg} at the earliest possible moment.
 *
 * <p>Android creates ContentProviders after {@code attachBaseContext} but before
 * {@code Application.onCreate}, so loading here is what guarantees the settings
 * are available to {@link ShareProvider} as well as to the Activity.
 */
public class SakiikaApplication extends Application {

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        try {
            Cfg.load(base);
        } catch (Throwable t) {
            // Defaults are usable; a crash here would take the whole app down
            // before anything could report why.
            Log.e(Bridge.TAG, "設定の読み込みに失敗しました", t);
        }
    }
}
