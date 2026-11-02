package com.thumbzzz.flip2wake;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Restarts the service after a reboot or app update if the user left it enabled. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String a = intent.getAction();
        if ((Intent.ACTION_BOOT_COMPLETED.equals(a) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(a))
                && Prefs.enabled(context)) {
            FlipWakeService.start(context);
        }
    }
}
