package fr.alexis.babyphone;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;

final class Notifs {
    static final String CH_STATUS = "status";
    static final String CH_ALERT = "alerts";

    private Notifs() {}

    static void createChannels(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;

        NotificationChannel status = new NotificationChannel(
                CH_STATUS, "État du babyphone", NotificationManager.IMPORTANCE_LOW);
        status.setShowBadge(false);
        nm.createNotificationChannel(status);

        // Le son et la vibration des alertes sont joués par l'appli elle-même
        // (sur le canal « alarme »), pour rester audibles en mode silencieux.
        NotificationChannel alert = new NotificationChannel(
                CH_ALERT, "Alertes bébé", NotificationManager.IMPORTANCE_HIGH);
        alert.setSound(null, null);
        alert.enableVibration(false);
        alert.setBypassDnd(true);
        alert.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(alert);
    }

    static PendingIntent openApp(Context c) {
        Intent i = new Intent(c, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(c, 0, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static Notification.Action action(Context c, String label, Class<?> service, String act, int requestCode) {
        Intent i = new Intent(c, service);
        i.setAction(act);
        PendingIntent pi = PendingIntent.getService(c, requestCode, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Action.Builder(
                Icon.createWithResource(c, R.drawable.ic_notif), label, pi).build();
    }

    static Notification status(Context c, String title, String text, Notification.Action... actions) {
        Notification.Builder b = new Notification.Builder(c, CH_STATUS)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openApp(c));
        for (Notification.Action a : actions) {
            b.addAction(a);
        }
        return b.build();
    }

    static Notification alert(Context c, String title, String text, Notification.Action... actions) {
        Notification.Builder b = new Notification.Builder(c, CH_ALERT)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(title)
                .setContentText(text)
                .setCategory(Notification.CATEGORY_ALARM)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .setShowWhen(true)
                .setWhen(System.currentTimeMillis())
                .setContentIntent(openApp(c));
        for (Notification.Action a : actions) {
            b.addAction(a);
        }
        return b.build();
    }
}
