# Magnet115 V0.1

Android proof-of-concept for:
1. 115 QR login using the `alipaymini` device identity.
2. Encrypted local cookie persistence.
3. Manual magnet input UI.

## Important
The QR-login implementation follows the currently observed 115 endpoints:
- GET https://qrcodeapi.115.com/api/1.0/web/1.0/token/
- GET https://qrcodeapi.115.com/get/status/
- POST https://passportapi.115.com/app/1.0/alipaymini/1.0/login/qrcode/

The offline-transfer button is intentionally marked "next step": the legacy 115
offline API requires account-specific signing values. We should validate login
on a real account first, then implement the current signing flow rather than
shipping guessed endpoints.

Use only with content you are authorized to download.

## Build
Open this folder with Android Studio, allow Gradle sync, then Build > Build APK(s).
Minimum Android version: Android 8.0 (API 26).
