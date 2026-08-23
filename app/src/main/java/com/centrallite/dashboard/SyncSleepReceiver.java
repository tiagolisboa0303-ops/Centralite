package com.centrallite.dashboard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Runs only if SYNC did not reconnect during the 30-second grace period. */
public class SyncSleepReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            Intent open = new Intent(context, MainActivity.class);
            open.putExtra("shutdown_from_sync", true);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK |
                    Intent.FLAG_ACTIVITY_CLEAR_TOP |
                    Intent.FLAG_ACTIVITY_SINGLE_TOP);
            context.startActivity(open);
        } catch (Exception ignored) { }
    }
}
