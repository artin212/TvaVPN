# Aether Mobile — داکیومنت قابلیت‌های افزوده‌شده (bhs)

این سند خلاصه‌ی قابلیت‌هایی است که در Aether Mobile v1.2.3 به برنامه افزوده شدند.

## قابلیت‌های افزوده‌شده

1. **ویجت صفحه‌خانه** (`widget/AetherWidgetProvider.kt`)
   - نمایش زنده‌ی وضعیت اتصال + اتصال/قطع با یک لمس
   - `updatePeriodMillis=0` — بدون بیدارشدن دوره‌ای؛ به‌روزرسانی فقط هنگام تغییر وضعیت اتصال (از هوک موجود در `AetherVpnService.updateNotification`)
   - بازگشت به اپ در نبود رضایت VPN (همان جریان Quick Settings Tile)

2. **اندازه‌گیری پینگ** (`core/PingMonitor.kt` + نشان در `ConnectionMeta`)
   - پینگ TCP به 1.1.1.1:53 از مسیر SOCKS5 تانل (پینگ واقعی تانل)
   - بدون حلقه‌ی پولینگ: یک بار خودکار پس از اتصال، بعد فقط با لمس کاربر (مصرف باتری ≈ صفر)
   - تایم‌اوت سخت ۵ ثانیه

3. **آنبوردینگ اولین اجرا** (`ui/OnboardingScreen.kt` + `data/OnboardingStore.kt`)
   - سه صفحه: خوش‌آمد / انتخاب هوشمند پروتکل / حریم خصوصی
   - دقیقاً یک بار نمایش داده می‌شود (DataStore جداگانه `aether_onboarding`)

4. **صفحه‌ی گزارش خرابی** (`CrashReportActivity.kt`)
   - کنترل‌کننده‌ی خرابی `AetherApp` علاوه بر لاگ، فایل `last_crash.txt` می‌نویسد
   - در اجرای بعدی، گزارش با دکمه‌ی کپی/بستن نمایش داده می‌شود و پس از بستن حذف می‌شود
   - `exported=false` — فقط از داخل اپ باز می‌شود

5. **ویجت + Tile + نوتیفیکیشن همگام** — هر سه از هوک تغییر وضعیت `AetherVpnService.updateNotification` تغذیه می‌شوند.

## بهینه‌سازی مصرف منابع و باتری (اعمال‌شده)

- ویجت: `updatePeriodMillis=0` — بدون wake-up دوره‌ای؛ repaint فقط هنگام تغییر وضعیت.
- پینگ: بدون حلقه‌ی پولینگ؛ هر اندازه‌گیری = یک handshake با تایم‌اوت ۵ ثانیه.
- نظارت بر انجین (موجود، تأییدشده): به‌جای پولینگ ۲ ثانیه‌ای، روی خروج پروسه پارک می‌شود (`SUPERVISOR_WAIT_MS=60s`) — صفر wake-up در اتصال سالم.
- واکنش به تعویض شبکه (Wi‑Fi ↔ دیتا): نکته‌ی بهبود آینده در نظر گرفته شود؛ فعلاً مکانیزم quick-reconnect + supervisor پوشش می‌دهد.

## ممیزی امنیت تغییرات

- `AetherWidgetProvider`: `exported=false`؛ برودکست toggle با component صریح؛ `PendingIntent` با `FLAG_IMMUTABLE`.
- `CrashReportActivity`: `exported=false`؛ فقط MainActivity بازش می‌کند؛ فایل گزارش داخل sandbox اپ و پس از بستن حذف می‌شود.
- ویجت: بدون ذخیره‌ی داده‌ی حساس؛ اتصال از ویجت با آخرین پروفایل ذخیره‌شده در DataStore (sandbox اپ).
- مجوز جدیدی اضافه نشده است؛ `REQUEST_INSTALL_PACKAGES` همچنان حذف است؛ `allowBackup=false` و `network_security_config` دست‌نخورده.
- پینگ فقط به 1.1.1.1:53 و از مسیر تانل؛ هیچ داده‌ی کاربر ارسال نمی‌شود.
