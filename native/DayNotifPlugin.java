package app.tshia.calendar;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.Build;
import android.util.Base64;
import androidx.core.app.NotificationCompat;
import androidx.core.graphics.drawable.IconCompat;
import com.getcapacitor.*;
import com.getcapacitor.annotation.*;
import org.json.*;
import java.text.SimpleDateFormat;
import java.util.*;

@CapacitorPlugin(name = "DayNotif", permissions = @Permission(alias = "notif", strings = {"android.permission.POST_NOTIFICATIONS"}))
public class DayNotifPlugin extends Plugin {
  @PluginMethod public void show(PluginCall c) {
    Context x = getContext();
    x.getSharedPreferences("dn", 0).edit().putString("d", c.getData().toString()).apply();
    Rcv.post(x); Rcv.alarm(x); c.resolve();
  }
  @PluginMethod public void hide(PluginCall c) {
    Context x = getContext();
    x.getSharedPreferences("dn", 0).edit().clear().apply();
    ((NotificationManager) x.getSystemService(Context.NOTIFICATION_SERVICE)).cancel(7);
    c.resolve();
  }

  /** بعد از ریبوت، نیمه‌شب، و هر بار که کاربر اعلان را پاک کند، دوباره پست می‌شود. */
  public static class Rcv extends BroadcastReceiver {
    @Override public void onReceive(Context x, Intent i) { post(x); alarm(x); }
    static Bitmap bmp(String b) { byte[] a = Base64.decode(b, 0); return BitmapFactory.decodeByteArray(a, 0, a.length); }
    static void alarm(Context x) {
      Calendar c = Calendar.getInstance(); c.add(Calendar.DAY_OF_YEAR, 1);
      c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 1); c.set(Calendar.SECOND, 0);
      ((AlarmManager) x.getSystemService(Context.ALARM_SERVICE)).set(AlarmManager.RTC, c.getTimeInMillis(),
        PendingIntent.getBroadcast(x, 2, new Intent(x, Rcv.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
    }
    static void post(Context x) {
      try {
        String s = x.getSharedPreferences("dn", 0).getString("d", null); if (s == null) return;
        JSONObject o = new JSONObject(s); JSONArray a = o.getJSONArray("days");
        String k = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date()); JSONObject d = null;
        for (int n = 0; n < a.length(); n++) if (a.getJSONObject(n).getString("key").equals(k)) { d = a.getJSONObject(n); break; }
        if (d == null) return;
        NotificationManager m = (NotificationManager) x.getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) m.createNotificationChannel(new NotificationChannel("day", "تاریخ امروز", NotificationManager.IMPORTANCE_LOW));
        int f = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        String body = d.getString("body"); int col;
        try { col = Color.parseColor(o.getString("color")); } catch (Exception e) { col = 0xFF0E6B57; }
        Notification n = new NotificationCompat.Builder(x, "day")
          .setSmallIcon(IconCompat.createWithBitmap(bmp(d.getString("small"))))
          .setLargeIcon(bmp(d.getString("large")))
          .setContentTitle(d.getString("title")).setContentText(body.split("\n")[0])
          .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
          .setColor(col).setOngoing(true).setAutoCancel(false).setOnlyAlertOnce(true).setShowWhen(false)
          .setPriority(NotificationCompat.PRIORITY_LOW).setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
          .setContentIntent(PendingIntent.getActivity(x, 0, x.getPackageManager().getLaunchIntentForPackage(x.getPackageName()), f))
          .setDeleteIntent(PendingIntent.getBroadcast(x, 1, new Intent(x, Rcv.class), f)).build();
        n.flags |= Notification.FLAG_NO_CLEAR | Notification.FLAG_ONGOING_EVENT;
        m.notify(7, n);
      } catch (Exception e) { }
    }
  }
}
