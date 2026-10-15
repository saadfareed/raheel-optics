# Daymark logo

The user-provided Daymark logo is used as a transparent icon in onboarding, the app header, account screens, application splash, Android startup splash and launcher. The splash renders the name and tagline separately using theme-aware colors; the name and tagline wrap when needed. Notifications and themed launcher icons use a matching monochrome sun/check mark.

`daymark-logo-transparent.png` is the master transparent asset. Its Android copy is `android-res/drawable-nodpi/daymark_logo_transparent.png`. The Android copies are optimized to 512 × 512 pixels; the full-resolution masters are retained for future exports. Transparency surrounds the rounded navy icon; the navy interior and white check remain opaque. The supplied wordmark is replaced by accessible UI text so it remains readable on dark backgrounds. The bundled icon is used consistently even when a cached remote branding logo exists.

Prepared with the built-in imagegen tool from the supplied logo. The final edit prompt was:

> Use case: background-extraction. Edit target: supplied Daymark logo. Extract ONLY the existing square rounded navy app icon on the left (coral sun and white checkmark). Remove the off-white outside background and remove the Daymark wordmark on the right. Preserve the icon's navy rounded-square interior, coral sun, white checkmark, original shapes and colors exactly. Output a tightly framed square PNG with real alpha transparency outside the rounded icon, a small even transparent margin, no text, no shadows outside the icon, no redesign. This will be used as the Android app logo, splash mark and launcher foreground; the name Daymark will be rendered separately as accessible theme-aware UI text.

## Splash mark

`daymark-splash-mark.png` and its Android copy `android-res/drawable-nodpi/daymark_splash_mark.png` remove the navy tile entirely. Application and native Android splash screens use this separate coral sun / white check cutout on dark backgrounds. Light splash screens use the original navy-backed icon so the white check remains visible. Other app branding and the launcher retain the supplied rounded icon.

Prepared using imagegen from the transparent rounded icon; the final prompt requested a production-quality transparent cutout preserving the sun, four rays, checkmark geometry, spacing, proportions and coral/white colors, with no navy square, shadows, text or background.
