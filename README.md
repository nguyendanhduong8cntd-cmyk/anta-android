# Anta

Android app for finding sports venues and booking court time. Module `anta/`: Kotlin, Jetpack Compose, Material 3, one activity.

It talks to the backend kept in a [separate repository](https://github.com/nguyendanhduong8cntd-cmyk/alobo-backend) (checked out at `backend/`, ignored by this repository's git).

## What works

| Screen | Backed by |
|---|---|
| Home: best-rated venues, then the nearest ones (or all, by name, without a position), filtered by sport | `GET public/branches`, `GET public/sports` |
| Explore: sports with their venue counts, the user's favourites, a ranking of the best-rated venues, the best-priced ones | same, `GET users/me/favorites` |
| Venue list: any sport, ordered by distance, rating or price | `GET public/branches` |
| Search: name, address or sport, typed with or without Vietnamese accents | `GET public/branches?q=` |
| Map: every venue as a pin on OpenStreetMap, sport filter, venue card | same |
| Venue: photos, price lists, services, rules, directions, a heart to keep it as a favourite | `GET public/branches/{id}`, `.../services`, `POST`/`DELETE users/me/favorites/{id}` |
| Favourites: the venues the user gave a heart, latest first | `GET users/me/favorites` |
| Booking: 14-day calendar, free and taken hours per yard, 1–8 consecutive hours | `GET .../availability`, `POST booking-holds`, `POST bookings` |
| Payment and booking status, cancellation | `POST payment-intents`, `GET bookings/{id}`, `POST bookings/{id}/cancel` |
| My bookings | `GET users/me/bookings` |
| Sign in, sign up and password reset with a verification code | `public/auth/*` |
| Sign in with Google (also creates the account) | `POST public/auth/social` |
| Account: rename, terms, sign out, delete account (confirmed by password, or by Google for an account without one) | `users/me`, `public/content/terms` |

Not built: chat and push notifications, which the backend has endpoints for.

## Build and run (debug)

The debug build expects the backend on the development machine.

```powershell
# backend/: docker compose -f infra/compose.yaml --profile app up -d
.\gradlew.bat :anta:assembleDebug
adb install -r .\anta\build\outputs\apk\debug\anta-debug.apk
adb reverse tcp:3000 tcp:3000          # again after every reconnect of the phone
adb shell am start -n vn.anta.booking/.MainActivity
```

`local.properties` (ignored by git) may hold:

| Key | Meaning |
|---|---|
| `anta.debugApiUrl` | Backend address of the debug build. Default `http://127.0.0.1:3000/api/v1`; an emulator can also use `http://10.0.2.2:3000/api/v1`. |
| `anta.devKey` | The backend's `DEV_ADMIN_KEY`. With it, and `DEV_OTP_ENABLED=true` on the backend, the debug build shows "Lấy mã từ máy chủ thử nghiệm" on the code step and "Thanh toán thử nghiệm" on a booking, because a local backend can neither send a code nor take a payment. |

Plain HTTP is allowed only in debug builds and only to `127.0.0.1`, `localhost` and `10.0.2.2`.

## Sign in with Google

The app shows "Tiếp tục với Google" only when the backend names a Google client in `GET public/config`. Nothing about it is built into the app; it is switched on in Google Cloud Console and in the backend:

1. In a Google Cloud project, configure the OAuth consent screen, then under *APIs & Services > Credentials* create two OAuth client IDs:
   - type **Web application** — its client ID is what the backend checks sign-ins against;
   - type **Android** — package name `vn.anta.booking` and the SHA-1 of the key the APK is signed with. `.\gradlew.bat :anta:signingReport` prints the debug key's SHA-1; a release key needs its own Android client.
2. Put the Web client ID in the backend's `.env` as `GOOGLE_CLIENT_ID=` and restart the API.

The phone asks Google for an ID token addressed to that client and sends it to the backend, which verifies it with Google's public keys. An account created this way has no password; deleting it asks for a new Google sign-in instead.

## Release build

```powershell
.\gradlew.bat :anta:assembleRelease "-Panta.apiUrl=https://<host>/api/v1"
```

- The build fails without an `https://` backend address: a release has no development address to fall back on.
- R8 and resource shrinking are on. The development helpers live in `anta/src/debug` only, so a release contains neither the development key nor the code that calls the `dev/*` routes.
- Signing: set `anta.keystore`, `anta.keystorePassword`, `anta.keyAlias`, `anta.keyPassword` in `local.properties`; without them the APK is unsigned.

## Tests

```powershell
.\gradlew.bat :anta:testDebugUnitTest
```

They cover reading the backend's JSON, formatting, the hour-selection rules, the token-renewal rules, Google sign-in and account deletion requests, and the favourites store (the last four against a local HTTP server).

## Diagnostics

Debug builds log screens, bottom-bar taps and requests under the `Anta` tag; passwords, codes and tokens are masked.

```powershell
adb logcat -s Anta:D *:S
```

## Layout

- `anta/src/main/java/vn/anta/booking/data` — backend client, JSON, session storage (encrypted with an Android Keystore key), favourites, location, formatting.
- `anta/src/main/java/vn/anta/booking/ui` — one package per screen, `components` for shared pieces (header band, search bar, venue cards), `theme` for colours and type.
- `tools/make_app_icons.py` — regenerates every launcher and in-app logo file from `tools/branding/anta-logo.png`.
- `anta/licenses` — licence of the bundled Be Vietnam Pro font.

Map tiles come from OpenStreetMap's public tile server, which is meant for light use; an app with real traffic needs its own tile provider.
