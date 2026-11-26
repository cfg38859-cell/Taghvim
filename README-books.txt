پایگاه‌داده‌ی بومی کتاب‌ها (SQLite) — جایگزینی ذخیره و جستجوی ۱۰۰۰ جلد

این سه فایل را روی همان مسیر در ریپوی GitHub جایگزین/اضافه کنید:
  index.html                     (ریشه)
  scripts/patch_android.py       (جایگزین فایل قبلی)
  native/TshBooksPlugin.java     (فایل جدید)
workflow و package.json را دست نزنید. بعد: Actions ← Build APK.

اولین اجرا: کتاب‌های قبلی (IndexedDB) خودکار به پایگاه‌داده منتقل و از IndexedDB پاک می‌شوند.
