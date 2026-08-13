package com.illusionlive.notifier;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Re-arms itself on every fire. AlarmManager has no periodic mode below 15 minutes,
 * and JobScheduler silently clamps setPeriodic() to 15 minutes.
 */
public final class FeedAlarmReceiver extends BroadcastReceiver {
    static final String ACTION_CHECK = "com.illusionlive.notifier.CHECK";
    private static final int REQUEST_CODE = 8701;

    static void sync(Context context) {
        Context app = context.getApplicationContext();
        AlarmManager alarms = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pending = pendingCheck(app);
        alarms.cancel(pending);
        if (!FeedChecker.backgroundEnabled(app)) return;
        // ponytail: setAndAllowWhileIdle avoids the SCHEDULE_EXACT_ALARM permission, but Doze
        // throttles it to roughly one fire per 9 minutes. Move to a foreground service if the
        // 1 minute target has to hold while the screen is off.
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + FeedChecker.POLL_MINUTES * 60_000L, pending);
    }

    private static PendingIntent pendingCheck(Context app) {
        Intent intent = new Intent(app, FeedAlarmReceiver.class).setAction(ACTION_CHECK);
        return PendingIntent.getBroadcast(app, REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    @Override public void onReceive(Context context, Intent intent) {
        final Context app = context.getApplicationContext();
        String action = intent == null ? null : intent.getAction();
        boolean isCheck = ACTION_CHECK.equals(action);
        if (!isCheck && !Intent.ACTION_BOOT_COMPLETED.equals(action)) return;

        sync(app); // chain the next fire first, so a failed fetch cannot end the loop
        if (!isCheck || !FeedChecker.backgroundEnabled(app)) return;

        final PendingResult done = goAsync();
        FeedChecker.check(app, true, new FeedChecker.Listener() {
            @Override public void onComplete(FeedChecker.Result result) { done.finish(); }
        });
    }
}
