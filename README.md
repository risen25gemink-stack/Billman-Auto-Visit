# Billman Auto Visit — Foundation + Cloud Build

Project Android native Kotlin untuk fondasi aplikasi **Billman Auto Visit**.

## Identitas
- Application ID: `com.billmanautovisit.app`
- Target Billman package: `dev.iconpln.mims`
- Versi foundation: `0.1.0-foundation`

## Scope saat ini
Tahap ini hanya berisi fondasi project:
- MainActivity kontrol Start / Stop
- Status Accessibility Service
- Foreground Service + notifikasi persisten
- BillmanAccessibilityService
- Skeleton ScreenReader / AutomationEngine / CameraTapper / LocalDataStore
- Belum ada implementasi state machine Rules.md

## Build di GitHub Actions
Workflow `.github/workflows/build-apk.yml` otomatis membuild debug APK pada setiap push ke `main`/`master`, pull request, atau manual `workflow_dispatch`.

Artifact hasil build bernama:

`Billman-Auto-Visit-debug`

File APK di dalam artifact:

`app-debug.apk`

## Toolchain cloud
- JDK 17
- Android SDK 36
- Android Build Tools 36.0.0
- Gradle 9.6.0
- Android Gradle Plugin 9.4.0

Project sengaja tidak menyimpan Gradle wrapper pada tahap ini. GitHub Actions menyediakan Gradle 9.6.0 melalui `gradle/actions/setup-gradle`, lalu menjalankan:

```bash
gradle --no-daemon :app:assembleDebug
```

## Target Android
`compileSdk = 36`, sedangkan `targetSdk = 32` untuk foundation awal agar permission foreground service tetap minimal sesuai dokumen acuan. Target SDK dapat dinaikkan setelah service baseline berhasil diuji di perangkat.

Build trigger aktif.
