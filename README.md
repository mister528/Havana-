# Havana Launcher

لانشر احترافي لسيرفر SAMP مكتوب بـ Kotlin لأندرويد. صُمّم خصيصاً لحل المشاكل الثلاث الرئيسية في عملية تحميل ملفات اللعبة:

1. **استئناف التحميل (Resume)** — لو خرج اللاعب من اللانشر أثناء التحميل، يكمل من نفس النقطة بدل ما يعيد من الصفر.
2. **التحميل في الخلفية** — التحميل يستمر داخل Foreground Service حتى لو خرج اللاعب من واجهة اللانشر، ولا يتوقف إلا عند إغلاق التطبيق نهائياً.
3. **اكتشاف الملفات الموجودة مسبقاً** — لو كانت ملفات `LuxuryMobile` موجودة على الجهاز من قبل، اللانشر يتحقق منها ويتخطّى التحميل تلقائياً.

---

## بنية المشروع

| الملف | الوظيفة |
|------|---------|
| `core/DownloadController.kt` | الـ State Machine الرئيسية، يُديرة كل عملية التحميل والتركيب. |
| `core/ResumableDownloader.kt` | تنزيل ملف واحد مع دعم `HTTP Range` و `.part` و `.meta` sidecar. |
| `core/DownloadMeta.kt` | يخزّن تقدّم التحميل على القرص بحيث ينجو من قتل التطبيق. |
| `core/FileVerifier.kt` | يقرر إذا كان الملف موجود مسبقاً وسليم (size + SHA-256). |
| `core/Installer.kt` | يحرّك الملف لمكانه النهائي أو يفك ضغط الـ ZIP. |
| `core/ManifestSource.kt` | يقرأ `launcher_manifest.json` من السيرفر، مع fallback للـ ZIP القديم. |
| `service/DownloadService.kt` | Foreground Service مع Notification، يبقي التحميل شغّال حتى لو الواجهة اتقفلت. |
| `ui/MainActivity.kt` | يطلب الصلاحيات ثم يفتح شاشة التحميل. |
| `ui/InstallActivity.kt` | الواجهة. تعرض حالة الـ Controller، ولا تشتغل تحميل بنفسها. |

---

## كيف يحل المشاكل الثلاث

### 1. استئناف التحميل بعد الخروج

كل ملف يُحفظ كـ `<key>.part` داخل المجلد الخاص بالتطبيق، وبجانبه `<key>.meta` (JSON صغير) يحتوي على:

- `url`, `total`, `downloaded`
- `etag` و `Last-Modified` (للتأكد إن السيرفر ما غيّر الملف بين الجلسات)

كل **256 KB** من التحميل يتم تحديث ملف الـ meta. لما يفتح اللاعب اللانشر تاني، الـ `ResumableDownloader`:

1. يقرأ الـ `.meta` ويعرف من وين يكمل.
2. يرسل `Range: bytes=<offset>-` للسيرفر.
3. لو السيرفر رد بـ `206 Partial Content` → يكتب على الـ `.part` من نفس النقطة.
4. لو السيرفر تجاهل الـ Range (نادر) → يبدأ من جديد تلقائياً بدل ما يخرّب الملف.
5. لو الـ ETag ما توافقش → يعيد من الصفر (يعني الملف على السيرفر اتغيّر).

### 2. التحميل يكمل لما تطلع من الواجهة

`DownloadService` هو الـ owner الحقيقي للتحميل، وهو `startForegroundService` + `setOngoing(true)` notification. النشاطات (Activities) **بس بتعرض** الـ state من `DownloadController.state`، ما بتشتغل تحميل بنفسها. النتيجة:

- لو رجع اللاعب لشاشة الـ home → التحميل مكمّل (السيستم بيدّيله أولوية data sync).
- لو شغل اللاعب لعبة تانية → التحميل مكمّل.
- لو السيستم قتل الـ Service بسبب الذاكرة → `START_STICKY` + الـ `.part`/`.meta` يخلّوه يكمل من نفس النقطة لما يرجع.
- التحميل بس بيتوقف لما يقفل اللاعب التطبيق تماماً (swipe من recents) أو يضغط Retry بعد فشل غير قابل للاسترداد.

### 3. اكتشاف الملفات الموجودة مسبقاً

عند كل تشغيل، `DownloadController` بيمر على كل بند في الـ manifest وبيسأل `FileVerifier.isSatisfied`:

- **ملف عادي**: حجم القرص = الحجم المتوقع؟ و (لو فيه SHA-256) الـ hash مطابق؟
- **أرشيف مفكوك**: مجلد الوجهة موجود؟ وفيه `.installed` marker بنفس الـ SHA-256 المتوقع؟

البنود اللي اتأكد إنها سليمة بتتشال من الـ queue قبل ما اللانشر يفتح أي connection شبكة. يعني لو اللاعب فعلاً ناقل ملفاته يدوياً من جهاز تاني، اللانشر بيدخّله اللعبة على طول من غير ما يحمّل أي شيء.

---

## شكل الـ manifest على السيرفر

ضع ملف JSON بسيط على `https://havanarpapo.zya.me/launcher_manifest.json`. لو الـ endpoint مش موجود اللانشر بيرجع تلقائياً للسلوك القديم (تنزيل `luxury.zip` من Dropbox).

```json
{
  "version": 24,
  "files": [
    {
      "url": "https://your-cdn/luxury.zip",
      "path": ".",
      "size": 367123456,
      "sha256": "abc123…",
      "extract": true
    },
    {
      "url": "https://your-cdn/patches/handling.cfg",
      "path": "data/handling.cfg",
      "size": 28119,
      "sha256": "def456…"
    }
  ]
}
```

| الحقل | إجباري؟ | الشرح |
|------|---------|------|
| `url` | نعم | رابط مباشر يدعم HTTP Range. |
| `path` | نعم | الوجهة بالنسبة لـ `/sdcard/LuxuryMobile/`. |
| `size` | يُفضّل | الحجم بالبايت. لو 0، اللانشر بيعتمد على `Content-Length` فقط. |
| `sha256` | يُفضّل بشدة | بيسمح بالتحقق من سلامة الملف وتفعيل skip-existing. |
| `extract` | لا | `true` يفك الـ ZIP. لو غير محدد، يتم تخمينه من امتداد `.zip`. |

---

## البناء

```bash
./gradlew :app:assembleDebug
# الناتج: app/build/outputs/apk/debug/app-debug.apk

./gradlew :app:assembleRelease
# يلزم توقيع. حدّث signing config في app/build.gradle.kts.
```

GitHub Actions يبني الـ APK تلقائياً عند كل push (راجع `.github/workflows/build.yml`) ويرفع `app-debug.apk` كـ artifact.

---

## دمج اللانشر مع اللعبة الحالية

اللانشر تطبيق مستقل بـ packageId خاص (`com.luxury.mobile.launcher`). بعد ما يخلّص التحميل بيشغل اللعبة (`com.luxury.mobile`) عن طريق `PackageManager.getLaunchIntentForPackage()`. لو حابب تدمج الاتنين في APK واحد:

1. غيّر `applicationId` في `app/build.gradle.kts` إلى `com.luxury.mobile`.
2. غيّر `versionCode` و `versionName` ليتطابقا مع اللعبة الحالية.
3. ادمج الـ smali للعبة (`GTASA`, `MenuActivity`, إلخ) داخل نفس الـ APK.

---

## License

Internal — Havana RP team.
