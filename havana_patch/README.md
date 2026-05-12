# Patch: استئناف التحميل + خلفية + كشف الملفات المثبتة

هذا المجلد يدمج ثلاث تحسينات داخل **اللانشر الأصلي** (`HavanaRp_v23.x`) دون
إعادة كتابته من الصفر. الناتج هو نفس الـ APK الموجود في `Havana_Rp_23.1_fixed.apk`
مع منطق تحميل بديل.

## ماذا يفعل الـ patch

1. **استئناف التحميل بعد الخروج (Resume).**
   نستبدل حلقة التحميل القديمة (`InstallActivity$1.run`) بكلاس Java جديد
   `HavanaDownloadRunner` يستخدم `HavanaSmartDownload`:
   - يفتح `HttpURLConnection` مع `Range: bytes=<offset>-` لو في
     `<path_zip>.part` على القرص.
   - يكتب على `<path_zip>.part` بـ `RandomAccessFile.seek(offset)` بدل ما يفتح
     `FileOutputStream` ويفلش الملف.
   - كل 256 KB يكتب `<path_zip>.meta` صغير فيه `downloaded|total|url` (atomic
     via .tmp + rename).
   - لو السيرفر تجاهل Range (رد 200 بدل 206) أو الـ ETag تغيّر، يحذف الـ .part
     ويعيد من الصفر بدلاً من تخريب الملف.
   - عند الاكتمال يعيد التسمية `.part → final` ويحذف الـ .meta.

2. **التحميل في الخلفية.**
   اللانشر الأصلي يبدأ `DownloadNotifyService` كـ Foreground Service في
   `InstallActivity.onCreate`. لكن التحميل نفسه كان على Thread عادي يتم قتله
   مع الـ activity. الحل: نفس الـ Thread، لكن `HavanaDownloadRunner` يحفظ
   التقدم على القرص ويستأنف من النقطة الأخيرة عند فتح اللانشر تاني — يعني
   حتى لو OS قتل العملية، التحميل بيكمّل من حيث وصل لا يبدأ من 0.

3. **اكتشاف الملفات المثبتة (Skip-existing).**
   كان الـ APK يحذف `/sdcard/LuxuryMobile/` كل مرة في `onCreate` (`file.delete()`)
   قبل ما يبدأ التحميل. أزلنا هذا الحذف، وأضفنا `HavanaInstallCheck.isAlreadyInstalled`
   اللي بيتأكد إن المجلد يحتوي على > 100 MB و ≥ 20 ملف — لو نعم، اللانشر
   بيخطى التحميل والـ Unzip ويفتح `MenuActivity` مباشرة. يشتغل سواء كانت
   الملفات منزّلة من اللانشر أو منقولة يدوياً من جهاز آخر.

## شجرة الملفات

```
havana_patch/
├── apply_patch.sh                         # سكربت يطبّق كل التعديلات + يبني + يوقّع
├── README.md                              # هذا الملف
├── stubs/                                 # stubs للـ compile فقط (لا تدخل الـ APK)
│   └── com/luxury/mobile/gui/InstallActivity.java
└── src/
    ├── com/luxury/mobile/util/
    │   ├── HavanaSmartDownload.java       # تحميل قابل للاستئناف
    │   └── HavanaInstallCheck.java        # كشف الملفات الموجودة مسبقاً
    └── com/luxury/mobile/gui/
        └── HavanaDownloadRunner.java      # يحل محل InstallActivity$1
```

## الاستخدام

تأكد إن عندك:

- `HavanaRp_v23.1_FINAL_pack/` الكامل (apktool template + 02_signing/keystore + 04_tools/)
- `ANDROID_HOME` يشير لـ Android SDK مع `platforms/android-35`
- `d8` على الـ PATH (موجود في `$ANDROID_HOME/build-tools/35.0.0/`)

ثم:

```bash
export ANDROID_HOME=$HOME/android-sdk
export PATH="$PATH:$ANDROID_HOME/build-tools/35.0.0"

cd havana_patch
./apply_patch.sh /path/to/HavanaRp_v23.1_FINAL_pack
```

النتيجة: `HavanaRp_v23.1_FINAL_pack/03_apktool_template/HavanaRp.apk` — موقّع
بنفس الـ keystore الموجود في `02_signing/havana.keystore`.

## تعديلات على ملفات الـ apktool template

السكربت بيحقن ثلاث ملفات smali جديدة وبيغيّر ملف موجود:

| الملف | التغيير |
|------|---------|
| `smali/com/luxury/mobile/util/HavanaSmartDownload.smali` (+ inner class) | جديد |
| `smali/com/luxury/mobile/util/HavanaInstallCheck.smali` | جديد |
| `smali/com/luxury/mobile/gui/HavanaDownloadRunner.smali` (+ inner classes) | جديد |
| `smali/com/luxury/mobile/gui/InstallActivity.smali` | تعديل: `getFileSize` تستخدم `HavanaDownloadRunner` بدل `InstallActivity$1`؛ `onCreate` لا تحذف `/sdcard/LuxuryMobile/` |

`InstallActivity$1.smali` بيبقى موجود لكن مش مستعمل (dead code) — مش بيؤثر
على حجم الـ APK كتير وبيخلّي الـ patch قابل للعكس بسهولة.

## أمن التوقيع

السكربت بيستعمل `02_signing/havana.keystore` (نفس الـ keystore اللي وقّع به
كل الإصدارات السابقة). يعني المستخدم القديم يقدر يحدّث الـ APK مباشرة بدون
ما يحذف ويعيد التثبيت.

## التحقق من الـ APK

```bash
apksigner verify --print-certs HavanaRp.apk
# Expected SHA-256: 59630a1584bfa99f8af37572a474a187233a1e9f5d71167c796d8f786060eb4f

aapt dump badging HavanaRp.apk | head -3
# package: name='com.luxury.mobile' versionCode='23' versionName='23.0'
```

## استرجاع التعديلات (rollback)

```bash
cd /path/to/HavanaRp_v23.1_FINAL_pack
rm -f 03_apktool_template/apk_full/smali/com/luxury/mobile/util/Havana{Smart,Install}*.smali
rm -f 03_apktool_template/apk_full/smali/com/luxury/mobile/gui/HavanaDownloadRunner*.smali
# ثم استرجاع InstallActivity.smali من نسخة احتياطية
```

أو ببساطة فك ضغط الـ APK الأصلي مرة تانية بـ apktool.
