# OTT App (Android + Server)

## 1. Server (movies upload/store)
    cd server
    npm install
    ADMIN_KEY=mysecret npm start
Browser me kholo: http://localhost:3000/admin -> admin key daalo, movie + thumbnail upload karo.

## 2. Android app
1. Android Studio me `android/` folder open karo (Gradle wrapper wo khud bana lega).
2. `Api.kt` me BASE_URL set karo:
   - Emulator: http://10.0.2.2:3000
   - Real phone: http://<PC-ka-IP>:3000 ya apna domain
3. Run karo.

## Production notes
- Server ko VPS par deploy karo (HTTPS lagao) aur manifest se `usesCleartextTraffic` hata do.
- Badi traffic ke liye videos S3/Cloudflare R2 + CDN par rakho, HLS (m3u8) use karo.
- Login/subscription chahiye to Firebase Auth ya JWT add karo.
