# HP Resetter (Android + CH341)

تطبيق أندرويد بديل لكود الأردوينو الأصلي — بيشتغل مباشرة مع محول **CH341** عبر
USB Host (OTG) على أي موبايل أندرويد، من غير الحاجة لجهاز كمبيوتر.

## المكتبة المستخدمة
[`usb-i2c-android`](https://github.com/3cky/usb-i2c-android) — بتدعم CH341 رسميًا
عبر USB Host، بدون root وبدون درايفرات إضافية.

## الوظائف
القائمة مطابقة تمامًا لكود الأردوينو الأصلي:
- 776xx Chipless
- 779xx Chipless
- 477-577 Error
- 556-586-Jam
- A3 Wast Chip
- Restore 000000

**الواجهة لا تعرض أي بيانات hex/بايتات** — فقط:
- إشعار "فشل الاتصال" لو مفيش شريحة أو حصل خطأ I2C
- إشعار "تم بنجاح ✅" لما تنجح الكتابة والتحقق
- شريط تقدّم (نسبة مئوية) أثناء التنفيذ

## طريقة الرفع على GitHub وتفعيل البناء التلقائي

```bash
cd HPResetter
git init
git add .
git commit -m "Initial commit"
git branch -M main
git remote add origin https://github.com/<username>/<repo-name>.git
git push -u origin main
```

بمجرد ما تعمل push، GitHub Actions (الملف الموجود في
`.github/workflows/build.yml`) هيبني الـ APK أوتوماتيك. تقدر تنزّله من
تبويب **Actions** → آخر run → **Artifacts** → `hpresetter-debug-apk`.

## قبل أول تشغيل على الموبايل
1. فعّل **"تثبيت من مصادر غير معروفة"** لتطبيق الملفات (File Manager/Chrome)
   عشان تقدر تثبت الـ APK يدويًا.
2. وصّل CH341 بكابل OTG بالموبايل — التطبيق مفروض يفتح تلقائي (auto-launch
   على attach) ويطلب صلاحية الوصول لجهاز USB أول مرة.

## ملاحظة تقنية
كل عملية I2C في الكود بتحاكي بالظبط نمط الـ Arduino Wire الأصلي:
كتابة `[addrHigh, addrLow, ...data]` كعملية واحدة للكتابة، وكتابة العنوان
منفصلة عن القراءة للقراءة — بنفس ترتيب العمليات اللي ظهر في تحليل لوجات
I2C الأصلية.
