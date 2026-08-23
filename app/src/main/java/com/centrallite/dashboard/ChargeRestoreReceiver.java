package com.centrallite.dashboard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Fail-safe used by the 15-second charging test. */
public class ChargeRestoreReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        new Thread(new Runnable() {
            @Override public void run() {
                PowerControl.setInputEnabled(true);
            }
        }, "CentralLiteChargeRestore").start();
    }
}
