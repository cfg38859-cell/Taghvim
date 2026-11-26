package app.tshia.calendar;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * کتابخانه‌ی کتاب‌ها روی دیسک (SQLite) + جستجوی بومی؛ همان روش «طلیعه نور».
 * متن هیچ‌وقت در حافظه‌ی جاوااسکریپت نمی‌ماند: صفحه‌ها و ایندکس متن در books.db هستند.
 * جداول: book (فهرست)، page (HTML صفحه‌ها)، ix (متن نرمال‌شده برای جستجو)، ic (همان متن بدون فاصله).
 * ty در ix: 0 متن، 1 پاورقی، 2 عنوان.
 */
@CapacitorPlugin(name = "TshBooks")
public class TshBooksPlugin extends Plugin {

    private static SQLiteDatabase db;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService sx = Executors.newSingleThreadExecutor();
    private static volatile int tok = 0;
    private static final ExecutorService scan = Executors.newFixedThreadPool(3);

    private static synchronized SQLiteDatabase db(android.content.Context c) {
        if (db == null || !db.isOpen()) {
            File f = c.getDatabasePath("books.db");
            f.getParentFile().mkdirs();
            db = SQLiteDatabase.openOrCreateDatabase(f, null);
            db.enableWriteAheadLogging();
            db.execSQL("CREATE TABLE IF NOT EXISTS book(id TEXT PRIMARY KEY, t TEXT, s TEXT, c TEXT, n INTEGER, toc TEXT, ord INTEGER)");
            db.execSQL("CREATE TABLE IF NOT EXISTS page(b TEXT, p INTEGER, h TEXT, PRIMARY KEY(b,p)) WITHOUT ROWID");
            db.execSQL("CREATE TABLE IF NOT EXISTS ix(id INTEGER PRIMARY KEY, b TEXT, p INTEGER, ty INTEGER, t TEXT)");
            db.execSQL("CREATE TABLE IF NOT EXISTS ic(id INTEGER PRIMARY KEY, c TEXT)");
            db.execSQL("CREATE INDEX IF NOT EXISTS ix_b ON ix(b)");
        }
        return db;
    }

    private interface Job { void run(SQLiteDatabase d) throws Exception; }

    private void work(final PluginCall call, final Job j) {
        io.execute(new Runnable() {
            @Override public void run() {
                try { j.run(db(getContext())); }
                catch (Throwable e) { call.reject(String.valueOf(e.getMessage())); }
            }
        });
    }

    @PluginMethod
    public void open(final PluginCall call) {
        work(call, new Job() { public void run(SQLiteDatabase d) {
            JSObject r = new JSObject(); r.put("ok", true); call.resolve(r);
        }});
    }

    @PluginMethod
    public void catalog(final PluginCall call) {
        work(call, new Job() { public void run(SQLiteDatabase d) throws Exception {
            JSArray a = new JSArray();
            Cursor c = d.rawQuery("SELECT id,t,s,c,n FROM book ORDER BY ord", null);
            try {
                while (c.moveToNext()) {
                    JSObject o = new JSObject();
                    o.put("id", c.getString(0)); o.put("t", c.getString(1)); o.put("s", c.getString(2));
                    o.put("c", c.getString(3)); o.put("n", c.getInt(4));
                    a.put(o);
                }
            } finally { c.close(); }
            JSObject r = new JSObject(); r.put("books", a); call.resolve(r);
        }});
    }

    private static void wipe(SQLiteDatabase d, String id) {
        d.execSQL("DELETE FROM ic WHERE id IN (SELECT id FROM ix WHERE b=?)", new Object[]{id});
        d.execSQL("DELETE FROM ix WHERE b=?", new Object[]{id});
        d.execSQL("DELETE FROM page WHERE b=?", new Object[]{id});
        d.execSQL("DELETE FROM book WHERE id=?", new Object[]{id});
    }

    @PluginMethod
    public void putBook(final PluginCall call) {
        work(call, new Job() { public void run(SQLiteDatabase d) throws Exception {
            String id = call.getString("id");
            d.beginTransaction();
            try {
                wipe(d, id);
                long ord = 1;
                Cursor c = d.rawQuery("SELECT IFNULL(MAX(ord),0)+1 FROM book", null);
                try { if (c.moveToFirst()) ord = c.getLong(0); } finally { c.close(); }
                SQLiteStatement s = d.compileStatement("INSERT INTO book(id,t,s,c,n,toc,ord) VALUES(?,?,?,?,?,?,?)");
                s.bindString(1, id);
                s.bindString(2, nz(call.getString("t")));
                s.bindString(3, nz(call.getString("s")));
                s.bindString(4, nz(call.getString("c")));
                s.bindLong(5, call.getInt("n", 0));
                s.bindString(6, nz(call.getString("toc")));
                s.bindLong(7, ord);
                s.executeInsert();
                d.setTransactionSuccessful();
            } finally { d.endTransaction(); }
            call.resolve();
        }});
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private static void addIx(SQLiteStatement si, SQLiteStatement sc, String b, int p, int ty, String t, String c) {
        si.bindString(1, b); si.bindLong(2, p); si.bindLong(3, ty); si.bindString(4, t);
        long id = si.executeInsert();
        sc.bindLong(1, id); sc.bindString(2, c);
        sc.executeInsert();
    }

    /** rows: [{h, t, c, u, uc}] برای صفحه‌های from … from+n-1 */
    @PluginMethod
    public void putPages(final PluginCall call) {
        work(call, new Job() { public void run(SQLiteDatabase d) throws Exception {
            String b = call.getString("id");
            int from = call.getInt("from", 0);
            JSArray rows = call.getArray("rows");
            d.beginTransaction();
            try {
                SQLiteStatement sp = d.compileStatement("INSERT OR REPLACE INTO page(b,p,h) VALUES(?,?,?)");
                SQLiteStatement si = d.compileStatement("INSERT INTO ix(b,p,ty,t) VALUES(?,?,?,?)");
                SQLiteStatement sc = d.compileStatement("INSERT INTO ic(id,c) VALUES(?,?)");
                for (int i = 0; i < rows.length(); i++) {
                    JSONObject r = rows.getJSONObject(i);
                    int p = from + i;
                    sp.bindString(1, b); sp.bindLong(2, p); sp.bindString(3, r.optString("h", ""));
                    sp.executeInsert();
                    String t = r.optString("t", "");
                    if (t.length() > 0) addIx(si, sc, b, p, 0, t, r.optString("c", ""));
                    String u = r.optString("u", "");
                    if (u.length() > 0) addIx(si, sc, b, p, 1, u, r.optString("uc", ""));
                }
                d.setTransactionSuccessful();
            } finally { d.endTransaction(); }
            call.resolve();
        }});
    }

    /** عنوان‌های فهرست: rows: [{p, t, c}] */
    @PluginMethod
    public void putHeads(final PluginCall call) {
        work(call, new Job() { public void run(SQLiteDatabase d) throws Exception {
            String b = call.getString("id");
            JSArray rows = call.getArray("rows");
            d.beginTransaction();
            try {
                SQLiteStatement si = d.compileStatement("INSERT INTO ix(b,p,ty,t) VALUES(?,?,?,?)");
                SQLiteStatement sc = d.compileStatement("INSERT INTO ic(id,c) VALUES(?,?)");
                for (int i = 0; i < rows.length(); i++) {
                    JSONObject r = rows.getJSONObject(i);
                    addIx(si, sc, b, r.optInt("p", 0), 2, r.optString("t", ""), r.optString("c", ""));
                }
                d.setTransactionSuccessful();
            } finally { d.endTransaction(); }
            call.resolve();
        }});
    }

    @PluginMethod
    public void delBook(final PluginCall call) {
        work(call, new Job() { public void run(SQLiteDatabase d) throws Exception {
            d.beginTransaction();
            try { wipe(d, call.getString("id")); d.setTransactionSuccessful(); }
            finally { d.endTransaction(); }
            call.resolve();
        }});
    }

    @PluginMethod
    public void getBook(final PluginCall call) {
        work(call, new Job() { public void run(SQLiteDatabase d) throws Exception {
            Cursor c = d.rawQuery("SELECT t,s,c,n,toc FROM book WHERE id=?", new String[]{call.getString("id")});
            try {
                if (!c.moveToFirst()) { call.reject("nobook"); return; }
                JSObject r = new JSObject();
                r.put("t", c.getString(0)); r.put("s", c.getString(1)); r.put("c", c.getString(2));
                r.put("n", c.getInt(3)); r.put("toc", c.getString(4));
                call.resolve(r);
            } finally { c.close(); }
        }});
    }

    @PluginMethod
    public void getPages(final PluginCall call) {
        work(call, new Job() { public void run(SQLiteDatabase d) throws Exception {
            JSArray a = new JSArray();
            Cursor c = d.rawQuery("SELECT h FROM page WHERE b=? AND p>=? AND p<? ORDER BY p",
                new String[]{call.getString("id"), String.valueOf(call.getInt("from", 0)), String.valueOf(call.getInt("to", 0))});
            try { while (c.moveToNext()) a.put(c.getString(0)); } finally { c.close(); }
            JSObject r = new JSObject(); r.put("pages", a); call.resolve(r);
        }});
    }

    // ───────────────────────── جستجو ─────────────────────────
    // جدول ix به تکه‌های ۴۰۰۰تایی (بر پایه‌ی id) تقسیم می‌شود و ۳ رشته هم‌زمان آن‌ها را می‌خوانند.
    // لغو جستجو بین تکه‌ها بررسی می‌شود (بدون CancellationSignal).

    @PluginMethod
    public void search(final PluginCall call) {
        final int my = ++tok;
        sx.execute(new Runnable() {
            @Override public void run() {
                try {
                    if (my != tok) { call.reject("cancelled"); return; }
                    call.resolve(doSearch(db(getContext()), call.getData(), my));
                } catch (Throwable e) {
                    if (my != tok || "cancelled".equals(e.getMessage())) { call.reject("cancelled"); return; }
                    logErr(e);
                    call.reject("native search: " + e);
                }
            }
        });
    }

    private void logErr(Throwable e) {
        try {
            java.io.FileWriter w = new java.io.FileWriter(new File(getContext().getFilesDir(), "books-error.txt"), true);
            w.write(new java.util.Date() + " " + android.util.Log.getStackTraceString(e) + "\n");
            w.close();
        } catch (Throwable ignored) { }
    }

    private static boolean bd(char ch) {
        return (ch >= 0x0600 && ch <= 0x06FF) || (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9') || ch == '_';
    }

    private static int cnt(String t, String w, boolean ww) {
        int c = 0, i = 0;
        if (w.length() == 0) return 0;
        while (c < 30 && (i = t.indexOf(w, i)) >= 0) {
            if (!ww || ((i == 0 || !bd(t.charAt(i - 1))) && (i + w.length() >= t.length() || !bd(t.charAt(i + w.length()))))) c++;
            i += w.length();
        }
        return c;
    }

    private static final Pattern FNX = Pattern.compile("<div class=\"fnx\">[\\s\\S]*?</div>");
    private static final Pattern TAGS = Pattern.compile("(?:<[^>]+>)+");
    private static final Pattern NUM = Pattern.compile("&#(\\d+);");
    private static final Pattern WS = Pattern.compile("[\\s\\u00a0\\ufeff\\u2028\\u2029\\u200c\\u1680\\u2000-\\u200a\\u202f\\u205f\\u3000]+");

    /** معادل rawTxt در برنامه: متن خام (با اعراب) صفحه یا پاورقی */
    private static String rawText(String h, int ty) {
        String body = h, f = "";
        if (h.indexOf("<div class=\"fnx\">") >= 0) {
            java.util.regex.Matcher m = FNX.matcher(h);
            StringBuilder fb = new StringBuilder();
            while (m.find()) fb.append(' ').append(m.group());
            f = fb.toString();
            body = FNX.matcher(h).replaceAll(" ");
        }
        String s = ty == 1 ? f : body;
        s = TAGS.matcher(s).replaceAll(" ");
        if (s.indexOf('&') >= 0) {
            s = s.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"");
            java.util.regex.Matcher m = NUM.matcher(s);
            StringBuffer sb = new StringBuffer();
            while (m.find()) m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(String.valueOf((char) Integer.parseInt(m.group(1)))));
            m.appendTail(sb);
            s = sb.toString().replace("&amp;", "&");
        }
        return WS.matcher(s).replaceAll(" ");
    }

    private static final class Hit {
        double sc; int i; String b; int p; int ty; String t;
    }

    private static final class Part {
        int all = 0, total = 0;
        HashMap<String, Integer> cm = new HashMap<String, Integer>();
        ArrayList<Hit> hits = new ArrayList<Hit>();
    }

    private static final class Q {
        String[] W, N, RW;
        String P, mode, scope, rph;
        boolean ww, comp, ex, byScore;
        int K;
        Set<String> allow;
    }

    private static String[] arr(JSONObject q, String k) {
        JSONArray a = q.optJSONArray(k);
        if (a == null) return new String[0];
        String[] r = new String[a.length()];
        for (int i = 0; i < r.length; i++) r[i] = a.optString(i, "");
        return r;
    }

    private static final Comparator<Hit> WORST_FIRST = new Comparator<Hit>() {
        public int compare(Hit a, Hit b) {
            if (a.sc != b.sc) return a.sc < b.sc ? -1 : 1;
            return a.i > b.i ? -1 : (a.i < b.i ? 1 : 0);
        }
    };

    /** یک تکه از جدول (id بین lo و hi) را می‌خواند و امتیاز می‌دهد؛ ترتیب و فرمول مثل srch در جاوااسکریپت. */
    private static Part scanChunk(SQLiteDatabase d, String sql, ArrayList<String> baseArgs, long lo, long hi, Q q, int my) {
        Part part = new Part();
        ArrayList<String> args = new ArrayList<String>(baseArgs);
        args.add(String.valueOf(lo)); args.add(String.valueOf(hi));
        PriorityQueue<Hit> heap = q.byScore ? new PriorityQueue<Hit>(q.K + 1, WORST_FIRST) : null;
        boolean wmode = q.mode.equals("ph");
        int seq = 0, nrow = 0;
        Cursor c = d.rawQuery(sql, args.toArray(new String[0]));
        try {
            while (c.moveToNext()) {
                if ((++nrow & 255) == 0 && my != tok) throw new RuntimeException("cancelled");
                String b = c.getString(1);
                if (q.allow != null && !q.allow.contains(b)) continue;
                int ty = c.getInt(3);
                String tn = c.getString(4);
                String t = q.comp ? c.getString(5) : tn;
                if (t == null) continue;
                double sc = 0;
                if (wmode) sc = cnt(t, q.P, q.ww);
                else {
                    boolean allw = true; int any = 0;
                    for (String w : q.W) {
                        int k = cnt(t, w, q.ww);
                        if (k > 0) { any++; sc += k; } else { allw = false; if (q.mode.equals("all")) break; }
                    }
                    if (q.mode.equals("all") ? !allw : any == 0) continue;
                    sc += any * 3;
                    if (q.W.length > 1 && t.contains(q.P)) sc += 8;
                }
                if (sc == 0) continue;
                if (q.ex && ty != 2) {
                    String h = null;
                    Cursor pc = d.rawQuery("SELECT h FROM page WHERE b=? AND p=?", new String[]{b, String.valueOf(c.getInt(2))});
                    try { if (pc.moveToFirst()) h = pc.getString(0); } finally { pc.close(); }
                    String r = rawText(h == null ? "" : h, ty);
                    boolean ok;
                    if (wmode) ok = r.contains(q.rph);
                    else {
                        boolean anyH = false, every = true;
                        for (String w : q.RW) { if (r.contains(w)) anyH = true; else every = false; }
                        ok = q.mode.equals("any") ? anyH : every;
                    }
                    if (!ok) continue;
                }
                part.all++;
                Integer old = part.cm.get(b); part.cm.put(b, old == null ? 1 : old + 1);
                if (!(q.scope.equals("all") || q.scope.equals(b))) continue;
                part.total++;
                Hit hh = new Hit();
                hh.sc = sc / (1 + t.length() / 3000.0); hh.i = seq++; hh.b = b; hh.p = c.getInt(2); hh.ty = ty; hh.t = tn;
                if (q.byScore) {
                    if (heap.size() < q.K) heap.add(hh);
                    else if (hh.sc > heap.peek().sc) { heap.poll(); heap.add(hh); }
                } else if (part.hits.size() < q.K) part.hits.add(hh);
            }
        } finally { c.close(); }
        if (q.byScore) part.hits.addAll(heap);
        return part;
    }

    /**
     * پارامترها را برنامه دقیقاً مثل srch جاوااسکریپت آماده می‌کند:
     * W کلمات، N کلمات منفی، P عبارت، mode(all|any|ph)، ww، comp (بدون فاصله)، ty(all|x|f|h)،
     * books (فهرست مجاز دسته یا null)، scope، ex و rw/rph (اعراب دقیق)، K (تعداد نتیجه)، sort(rel|ord)
     */
    private JSObject doSearch(final SQLiteDatabase d, JSONObject jq, final int my) throws Exception {
        final Q q = new Q();
        q.W = arr(jq, "W"); q.N = arr(jq, "N"); q.RW = arr(jq, "rw");
        q.P = jq.optString("P", ""); q.mode = jq.optString("mode", "all"); q.scope = jq.optString("scope", "all"); q.rph = jq.optString("rph", "");
        String tyq = jq.optString("ty", "all");
        q.ww = jq.optBoolean("ww", false); q.comp = jq.optBoolean("comp", false); q.ex = jq.optBoolean("ex", false);
        q.K = Math.max(1, jq.optInt("K", 400)); q.byScore = jq.optString("sort", "rel").equals("rel");
        if (jq.has("books") && !jq.isNull("books")) { q.allow = new HashSet<String>(); for (String s : arr(jq, "books")) q.allow.add(s); }
        JSObject out = new JSObject(), counts = new JSObject();
        JSArray rowsOut = new JSArray();
        if (q.W.length == 0) { out.put("all", 0); out.put("cnt", counts); out.put("rows", rowsOut); out.put("total", 0); return out; }

        StringBuilder sb = new StringBuilder();
        final ArrayList<String> args = new ArrayList<String>();
        String col = q.comp ? "c.c" : "x.t";
        if (q.comp) sb.append("SELECT x.id,x.b,x.p,x.ty,x.t,c.c FROM ix x JOIN ic c ON c.id=x.id WHERE 1=1");
        else sb.append("SELECT x.id,x.b,x.p,x.ty,x.t FROM ix x WHERE 1=1");
        if (tyq.equals("all")) sb.append(" AND x.ty IN (0,1)");
        else sb.append(" AND x.ty=").append(tyq.equals("x") ? 0 : tyq.equals("f") ? 1 : 2);
        if (q.mode.equals("ph")) { sb.append(" AND instr(").append(col).append(",?)>0"); args.add(q.P); }
        else if (q.mode.equals("any")) {
            sb.append(" AND (");
            for (int i = 0; i < q.W.length; i++) { if (i > 0) sb.append(" OR "); sb.append("instr(").append(col).append(",?)>0"); args.add(q.W[i]); }
            sb.append(")");
        } else {
            for (String w : q.W) { sb.append(" AND instr(").append(col).append(",?)>0"); args.add(w); }
        }
        for (String n : q.N) { sb.append(" AND instr(").append(col).append(",?)=0"); args.add(n); }
        sb.append(" AND x.id BETWEEN ? AND ?");
        final String sql = sb.toString();

        long lo = 0, hi = -1;
        Cursor mc = d.rawQuery("SELECT MIN(id),MAX(id) FROM ix", null);
        try { if (mc.moveToFirst() && !mc.isNull(0)) { lo = mc.getLong(0); hi = mc.getLong(1); } } finally { mc.close(); }
        final long CH = 4000;
        ArrayList<Future<Part>> fut = new ArrayList<Future<Part>>();
        for (long a = lo; a <= hi; a += CH) {
            final long fa = a, fb = Math.min(hi, a + CH - 1);
            fut.add(scan.submit(new Callable<Part>() {
                public Part call() {
                    if (my != tok) throw new RuntimeException("cancelled");
                    return scanChunk(d, sql, args, fa, fb, q, my);
                }
            }));
        }
        int all = 0, total = 0, offset = 0;
        HashMap<String, Integer> cm = new HashMap<String, Integer>();
        ArrayList<Hit> hits = new ArrayList<Hit>();
        try {
            for (Future<Part> f : fut) {
                Part p = f.get();
                all += p.all;
                for (Map.Entry<String, Integer> e : p.cm.entrySet()) { Integer o = cm.get(e.getKey()); cm.put(e.getKey(), o == null ? e.getValue() : o + e.getValue()); }
                for (Hit h : p.hits) { h.i += offset; hits.add(h); }
                offset += p.total; total += p.total;
            }
        } catch (Throwable e) {
            for (Future<Part> f : fut) f.cancel(true);
            Throwable c = e.getCause() != null ? e.getCause() : e;
            if (c instanceof RuntimeException && "cancelled".equals(c.getMessage())) throw new RuntimeException("cancelled");
            throw new Exception(c);
        }
        if (q.byScore) Collections.sort(hits, new Comparator<Hit>() {
            public int compare(Hit a, Hit b) {
                if (a.sc != b.sc) return a.sc > b.sc ? -1 : 1;
                return a.i < b.i ? -1 : (a.i > b.i ? 1 : 0);
            }
        });
        else Collections.sort(hits, new Comparator<Hit>() {
            public int compare(Hit a, Hit b) { return a.i < b.i ? -1 : (a.i > b.i ? 1 : 0); }
        });
        for (int k = 0; k < hits.size() && k < q.K; k++) {
            Hit h = hits.get(k);
            JSObject o = new JSObject();
            o.put("b", h.b); o.put("p", h.p); o.put("ty", h.ty); o.put("sc", h.sc); o.put("i", h.i); o.put("t", h.t);
            rowsOut.put(o);
        }
        for (Map.Entry<String, Integer> e : cm.entrySet()) counts.put(e.getKey(), e.getValue());
        out.put("all", all); out.put("cnt", counts); out.put("rows", rowsOut); out.put("total", total);
        return out;
    }
}
