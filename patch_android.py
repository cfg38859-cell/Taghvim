import shutil, pathlib
pkg = "app/tshia/calendar"
d = pathlib.Path("android/app/src/main/java") / pkg
shutil.copy("native/DayNotifPlugin.java", d / "DayNotifPlugin.java")
(d / "MainActivity.java").write_text("""package app.tshia.calendar;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
  @Override public void onCreate(Bundle b) {
    registerPlugin(DayNotifPlugin.class);
    super.onCreate(b);
  }
}
""", encoding="utf-8")
m = pathlib.Path("android/app/src/main/AndroidManifest.xml")
t = m.read_text(encoding="utf-8")
perms = ('<uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>\n'
         '    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED"/>\n    ')
rcv = ('<receiver android:name=".DayNotifPlugin$Rcv" android:exported="false">'
       '<intent-filter><action android:name="android.intent.action.BOOT_COMPLETED"/>'
       '<action android:name="android.intent.action.MY_PACKAGE_REPLACED"/></intent-filter></receiver>\n    ')
assert "<application" in t and "</application>" in t
t = t.replace("<application", perms + "<application", 1).replace("</application>", rcv + "</application>", 1)
m.write_text(t, encoding="utf-8")
print("patched")
