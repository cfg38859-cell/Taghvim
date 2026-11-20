package app.tshia.calendar;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;

/**
 * اعلان دائمی تاریخ (Persistent date notification)
 *
 * JS usage:
 *   const { DayNotif } = Capacitor.Plugins;
 *   await DayNotif.show({ title: "۲۹ آبان ۱۴۰۵", text: "شهادت ... " });
 *   await DayNotif.hide();
 */
@CapacitorPlugin(
    name = "DayNotif",
    permissions = {
        @Permission(strings = { Manifest.permission.POST_NOTIFICATIONS }, alias = "notifications")
    }
)
public class DayNotifPlugin extends Plugin {

    private static final String CHANNEL_ID = "day_notif_channel";
    private static final int NOTIF_ID = 7101;

    @PluginMethod
    public void show(PluginCall call) {
        // Android 13+: need runtime notification permission
        if (Build.VERSION.SDK_INT >= 33 && getPermissionState("notifications")
                != com.getcapacitor.PermissionState.GRANTED) {
            requestPermissionForAlias("notifications", call, "permissionCallback");
            return;
        }
        doShow(call);
    }

    @com.getcapacitor.annotation.PermissionCallback
    private void permissionCallback(PluginCall call) {
        if (getPermissionState("notifications") == com.getcapacitor.PermissionState.GRANTED) {
            doShow(call);
        } else {
            call.reject("notification permission denied");
        }
    }

    private void doShow(PluginCall call) {
        try {
            Context ctx = getContext();
            String title = call.getString("title", "تقویم شیعه");
            String text = call.getString("text", "");

            NotificationManager nm =
                (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "تاریخ روز", NotificationManager.IMPORTANCE_LOW);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }

            Intent open = new Intent(ctx, MainActivity.class);
            open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pi = PendingIntent.getActivity(ctx, 0, open, flags);

            Notification.Builder b = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? new Notification.Builder(ctx, CHANNEL_ID)
                : new Notification.Builder(ctx);

            b.setSmallIcon(ctx.getApplicationInfo().icon)
             .setContentTitle(title)
             .setContentText(text)
             .setStyle(new Notification.BigTextStyle().bigText(text))
             .setContentIntent(pi)
             .setOngoing(true)
             .setOnlyAlertOnce(true)
             .setShowWhen(false);

            nm.notify(NOTIF_ID, b.build());

            JSObject ret = new JSObject();
            ret.put("shown", true);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("show failed: " + e.getMessage());
        }
    }

    @PluginMethod
    public void hide(PluginCall call) {
        try {
            NotificationManager nm =
                (NotificationManager) getContext().getSystemService(Context.NOTIFICATION_SERVICE);
            nm.cancel(NOTIF_ID);
            call.resolve();
        } catch (Exception e) {
            call.reject("hide failed: " + e.getMessage());
        }
    }
}
