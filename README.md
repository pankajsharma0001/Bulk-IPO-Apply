<div align="center">

<img src="app/icon-assets/icon.png" width="128"/>

# IPO Apply

**A tiny native Android app for managing MeroShare IPO applications across saved accounts.**

![Kotlin](https://img.shields.io/badge/Kotlin-Native-145E48?style=for-the-badge&logo=kotlin&logoColor=white)
![Android](https://img.shields.io/badge/Android-11%2B-1BAE66?style=for-the-badge&logo=android&logoColor=white)
![No Compose](https://img.shields.io/badge/UI-No%20Compose-0F172A?style=for-the-badge)
![Small APK](https://img.shields.io/badge/APK-Tiny-0EA5E9?style=for-the-badge)

</div>

---

## Overview

IPO Apply is a lightweight Android app built for fast MeroShare IPO workflows. It lets users save multiple MeroShare accounts, view available IPOs, check whether each account has already applied, and apply for selected accounts with fewer repeated steps.

The app is intentionally built with a **minimum-size, native-first** philosophy:

- No Compose
- No Material Components
- No external UI libraries
- No image-heavy UI
- Native Android Views only
- Small release APK target

---

## Features

<table>
  <tr>
    <td><b>Multiple accounts</b></td>
    <td>Save and manage multiple MeroShare accounts on-device.</td>
  </tr>
  <tr>
    <td><b>Latest IPO list</b></td>
    <td>Fetch available IPOs from MeroShare when the app opens or when refreshed.</td>
  </tr>
  <tr>
    <td><b>Already-applied status</b></td>
    <td>Shows a clear status when an IPO is already applied for an account.</td>
  </tr>
  <tr>
    <td><b>Selective apply</b></td>
    <td>Choose which saved accounts should apply for a selected IPO.</td>
  </tr>
  <tr>
    <td><b>Account details</b></td>
    <td>View saved BOID, bank, CRN, and PIN with sensitive fields hidden by default.</td>
  </tr>
  <tr>
    <td><b>Native UX</b></td>
    <td>Clean loading, error, empty, selection, and confirmation states without UI dependencies.</td>
  </tr>
</table>

---

## Design Principles

> Small, fast, native, practical.

This project avoids heavy frameworks and favors direct Android platform APIs. Most UI is generated from Kotlin using `LinearLayout`, `TextView`, `Button`, `ImageView`, and `GradientDrawable`.

The goal is not to be flashy. The goal is to feel quick, clear, and dependable on real devices.

---

## Tech Stack

| Area       | Choice               |
| ---------- | -------------------- |
| Language   | Kotlin               |
| UI         | Native Android Views |
| Networking | `HttpURLConnection`  |
| Storage    | `SharedPreferences`  |
| JSON       | `org.json`           |
| Min SDK    | 23                   |
| Target SDK | 36                   |
| Package    | `com.rohit.ipoapply` |

---

## Build

Open the project in Android Studio and let Gradle sync.

To build from terminal:

```powershell
.\gradlew.bat assembleRelease
```

Release APK output:

```text
app/build/outputs/apk/release/app-release.apk
```

There is also a helper script:

```powershell
.\apk.ps1
```

---

## Project Structure

```text
Bulk-IPO-Apply/
├─ app/
│  ├─ src/main/
│  │  ├─ java/com/rohit/ipoapply/MainActivity.kt
│  │  ├─ res/drawable/
│  │  ├─ res/mipmap-anydpi-v26/
│  │  └─ AndroidManifest.xml
├─ gradle/
├─ build.gradle.kts
├─ settings.gradle.kts
└─ README.md
```

---

## Notes

- Saved account data is stored locally on the device.
- Tokens are reused in memory during the app session where possible.
- The app fetches the latest IPO list on launch when saved accounts exist.
- Network errors are shown in-app with user-friendly messages.

---

## Disclaimer

This is an independent project and is not affiliated with CDSC, MeroShare, or any official financial institution. Use carefully and verify applications through official channels when needed.

---

<div align="center">

Built with a stubborn love for tiny native apps.

</div>
