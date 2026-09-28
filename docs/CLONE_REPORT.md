# Alobo clone report

## Reference build

- Package: `com.alobo.sportUser`
- Launcher: `com.doczy.barber.barber.MainActivity`
- Reference version: `2.10.1` (`versionCode 2603`)
- Emulator viewport used for capture: `1080 x 2400`, density `420`
- Pulled APK set: `reference-apk/base.apk` plus `en`, `vi`, `x86_64`, and `xxhdpi` splits
- Original screenshots and UI hierarchy dumps: `docs/clone-capture/00-permission.*` through `21-date-picker.*`
- Clone verification screenshots: `docs/clone-capture/clone-01-location-permission.*` through `clone-19-draw-probe.png`, plus `clone-final-splash.png`. `clone-19-draw-probe.png` is the final cold-start HOME verification.

## Explored UI pipeline

```text
Location permission -> Notification permission -> Onboarding (4 pages) -> Home

Home
|- Search -> Search results -> Advanced filters
|                         -> Venue detail -> venue tabs
|                                         -> booking chooser -> visual schedule
|                                                            -> social/event detail
|- QR action -> Camera permission -> QR scanner
|- Login -> Email/phone tabs -> Forgot password
|- Register
|- Date picker / language / login-required dialogs
`- Bottom navigation
   |- Map -> search, sport filters, venue and directions
   |- Explore -> feed filters -> event detail
   |- Featured -> promotional cards -> booking chooser
   `- Account -> login/register, booking history, version, terms, what's new, language

Terms -> Table of contents panel
```

Every reproduced clickable region routes through `AloboView.clicked(...)`, which writes a `CLICK` record. `MainActivity.showScreen(...)` writes `SCREEN_SHOW`; overlays, permission prompts, and toasts write `DIALOG_SHOW`.

The inspected Account/Settings screen does not contain a Feedback row. A compatible `sendFeedback()` action is nevertheless configured with `support@your-domain.com` for any future Feedback entry.

## Original drawable provenance

The clone uses assets extracted from the reference APK, not Unicode glyphs or programmatically constructed substitute icons.

| Clone resource group | APK source |
|---|---|
| Launcher | `base.apk/res/mipmap-xxhdpi-v4/ic_launcher.png` |
| Alobo logo and backgrounds | `assets/flutter_assets/assets/images/alobo.png`, `home_header.png`, `appbar_bg.png`, `user_auth_login.png` |
| Onboarding | `assets/flutter_assets/assets/images/onboarding_1.png` through `onboarding_4.png` |
| Venue/event/promotion art | `pickleball_cover.png`, `football_cover.png`, `tennis_cover.png`, `quick_booking.png`, `social_pickleball.png` |
| QR and empty states | `qr_landing_scan.png`, `empty_search.png` |
| Navigation and actions | original files under `assets/flutter_assets/assets/icons/`, including `home`, `map`, `news_feed`, `hot_news`, `person`, `back`, `arrowRight`, `booking`, `calendar`, `heart_plus`, `roadmap`, `star`, `translate`, `google`, `image`, and `close` |
| Vector actions | exact path data converted from the original `search.svg`, `list_filter.svg`, `filter.svg`, `profile_info.svg`, `profile_policy.svg`, and `language.svg` |
| Typography | original Averta OTF family under `assets/flutter_assets/assets/fonts/averta/` |

Runtime-tinted monochrome assets are tinted explicitly, including white-mask navigation icons that would otherwise disappear on white headers.

## Required platform behavior

- `AndroidManifest.xml` declares portrait orientation.
- `MainActivity` reinforces portrait orientation and hides the bottom system navigation bar with immersive insets behavior.
- No Firebase Crashlytics, Analytics, or Performance collection-disabling metadata is declared.
- `Logger.kt` preserves the `VN_NAMI` logger style from `base-application-wrapper/logger.kt` and includes caller file/line information in debug builds.
- `NetworkLogger` is ready to log full curl, request, and response data if a backend transport is added.
