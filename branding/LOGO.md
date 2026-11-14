# Raheel Optics logo

- `raheel-optics-logo-concept.png`: original artwork (glasses, eye and "Raheel Optics" wordmark).
- `make_mark.py`: draws the app mark (glasses and eye, no text) and writes every file below. Edit the shapes or
  colours there and rerun `python3 branding/make_mark.py`.
- `raheel-optics-mark.svg`, `raheel-optics-mark-dark.svg`: the mark for light and dark backgrounds (for print, web, Play listing).
- `android-res/drawable/ic_brand_mark_light.xml`, `ic_brand_mark_dark.xml`: the in-app mark (header, sign-in, splash,
  loader). `BrandLogo` in `ui/CommonUi.kt` picks one from the app's theme; the splash picks one from the system theme.
- `android-res/drawable/ic_launcher_foreground.xml` + `ic_launcher_background.xml`: launcher icon (mark on white).
- `android-res/drawable/ic_launcher_monochrome.xml`: themed (tinted) launcher icon on Android 13+.
- `app/src/main/res/drawable/ic_notification.xml`: white glasses outline for notifications.
- `brand.properties`: app name, tagline and sync timings baked into the build.
