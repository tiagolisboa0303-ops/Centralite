package com.centrallite.dashboard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.view.KeyEvent;

/** Best-effort bridge for steering/headset voice buttons that Android actually receives. */
public class MediaButtonReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null || !Intent.ACTION_MEDIA_BUTTON.equals(intent.getAction())) return;
        KeyEvent event = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
        if (event == null || event.getAction() != KeyEvent.ACTION_UP) return;
        int code = event.getKeyCode();
        if (code != KeyEvent.KEYCODE_HEADSETHOOK &&
                !(android.os.Build.VERSION.SDK_INT >= 21 && code == KeyEvent.KEYCODE_VOICE_ASSIST)) return;
        try {
            Intent open = new Intent(context, MainActivity.class);
            open.putExtra("voice_from_steering", true);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            context.startActivity(open);
        } catch (Exception ignored) { }
    }
}
