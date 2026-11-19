package app.tshia.calendar;

import android.content.Context;
import android.widget.RemoteViews;
import org.json.JSONArray;
import org.json.JSONObject;

public class PrayerWidget extends WBase {
  @Override int layout() { return R.layout.tw_prayer; }

  @Override RemoteViews build(Context c, int id, int sz, boolean dark, JSONObject d, JSONObject cfg) throws Exception {
    if (d == null) return shell(c, layout(), cfg, dark, "برنامه‌ی «تقویم شیعه» را یک بار باز کنید تا ابزارک داده بگیرد");
    long now = System.currentTimeMillis(), mid = WStore.midnight(now);
    int di = WStore.dayIdx(d.getJSONArray("days"), mid);
    int ci = WStore.city(d, cfg);
    JSONArray pts = d.getJSONArray("pt").getJSONArray(ci);
    long[] nx = di < 0 ? null : WStore.nextAdhan(pts, now);
    if (nx == null) return shell(c, layout(), cfg, dark, "داده‌ی ابزارک قدیمی شده؛ برنامه را باز کنید");
    RemoteViews rv = shell(c, layout(), cfg, dark, null);
    int ink = ink(dark), mute = mute(dark), em = em(dark);
    txt(rv, R.id.tw_city, WStore.cityName(d, ci), mute, sz == 0 ? 11 : 12);
    txt(rv, R.id.tw_label, "تا " + WStore.PN[(int) nx[2]], ink, sz == 0 ? 13 : 15);
    count(rv, nx[1] - now, em, sz == 0 ? 26 : sz == 1 ? 32 : 44);
    progress(rv, dark, (int) Math.max(0, Math.min(1000, (now - nx[0]) * 1000 / Math.max(1, nx[1] - nx[0]))));
    txt(rv, R.id.tw_at, "ساعت " + WStore.hm(nx[1]), mute, sz == 0 ? 11 : 12);
    show(rv, R.id.tw_row, sz >= 1);
    if (sz >= 1) {
      JSONArray tt = pts.getJSONArray(di);
      for (int k = 0; k < 6; k++) {
        boolean nextOne = tt.getLong(k) == nx[1];
        txt(rv, rid(c, "tw_pn", k), WStore.PS[k], mute, sz == 2 ? 11 : 10);
        txt(rv, rid(c, "tw_pt", k), WStore.hm(tt.getLong(k)), nextOne ? em : ink, sz == 2 ? 13 : 12);
      }
    }
    return rv;
  }
}
