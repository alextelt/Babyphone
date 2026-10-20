package fr.alexis.babyphone;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Réveillé par une alarme si le téléphone bébé ne donne plus de nouvelles. */
public class WatchdogReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        ParentService s = ParentService.instance;
        if (s != null) {
            s.checkWatchdog();
        }
    }
}
