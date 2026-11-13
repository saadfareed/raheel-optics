# Raheel Optics logo

- `raheel-optics-logo-concept.png`: source artwork.
- `android-res/drawable-nodpi/raheel_optics_logo.png`: the copy the app uses (header, sign-in, splash, loader, launcher icon).
- `android-res/drawable/ic_launcher_foreground.xml`: launcher icon (the logo, inset).
- `android-res/drawable/ic_launcher_monochrome.xml`: themed-icon outline (an eye).
- `app/src/main/res/drawable/ic_notification.xml`: white glasses outline for notifications.
- `brand.properties`: app name, tagline and sync timings baked into the build.

In dark mode the app draws the logo on a white disc (`BrandLogo` in `ui/CommonUi.kt`) so the dark artwork stays readable.
