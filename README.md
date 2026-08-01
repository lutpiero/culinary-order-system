# 🍜 Culinary Order System

Sistem manajemen pesanan kuliner Indonesia dengan dua sisi: **Aplikasi Android untuk Penjual** dan **Antarmuka Web untuk Pelanggan**.

---

## Fitur Utama

### 📱 Aplikasi Android (Penjual)
- **Manajemen Pesanan** – Lihat dan perbarui status pesanan secara real-time (Antrian → Diproses → Siap → Disajikan)
- **Manajemen Menu** – Tambah/edit/hapus item menu, atur kategori, topping, dan harga
- **QR Code Meja** – Generate QR code per meja; pelanggan scan untuk membuka menu web
- **Laporan Keuangan** – Ringkasan penjualan harian/mingguan/bulanan dengan rincian per metode pembayaran
- **Notifikasi Push** – Firebase Cloud Messaging untuk pesanan masuk baru

### 🌐 Antarmuka Web (Pelanggan)
- Responsif dan mobile-first
- Navigasi menu dua level: item utama → topping/opsi bersyarat
- Checkout dengan pilihan pembayaran: **QRIS**, **Transfer Bank**, **Bayar di Kasir**
- **Multi-Customer Table Support** – Mendukung beberapa pelanggan memesan dari meja yang sama dengan riwayat pesanan terpisah
- Bahasa Indonesia

---

## Arsitektur

```
culinary-order-system/
├── app/                          # Android Seller App (Kotlin + Jetpack Compose)
│   └── src/main/kotlin/com/culinary/orderapp/
│       ├── data/                 # Repository implementations, DTOs, Firebase
│       ├── domain/               # Models, repository interfaces, use cases
│       ├── ui/                   # Compose screens, ViewModels, navigation
│       └── di/                   # Hilt dependency injection
├── web/                          # Customer Web Frontend (HTML/CSS/JS)
│   ├── index.html
│   ├── styles.css
│   └── app.js
├── .github/workflows/
│   ├── android.yml               # Android CI/CD (build, test, sign, upload)
│   └── web.yml                   # Web CI/CD (validate, deploy to Netlify)
└── netlify.toml                  # Netlify deployment config
```

### Tech Stack
| Layer | Teknologi |
|-------|-----------|
| Android UI | Kotlin + Jetpack Compose + Material3 |
| Architecture | MVVM + Clean Architecture (Domain/Data/UI) |
| DI | Hilt |
| Backend | Firebase Firestore (real-time sync) |
| Notifications | Firebase Cloud Messaging |
| Image Loading | Coil |
| QR Code | ZXing |
| Web Frontend | Vanilla HTML/CSS/JS (no framework, no build step) |
| CI/CD | GitHub Actions |
| Web Hosting | Netlify |

---

## Setup

### Android App

1. **Firebase** – Buat project Firebase dan unduh `google-services.json`, tempatkan di `app/`.
2. **Build** – Buka project di Android Studio atau jalankan:
   ```bash
   ./gradlew :app:assembleDebug
   ```
3. **Tests** – Jalankan unit tests:
   ```bash
   ./gradlew :app:testDebugUnitTest
   ```

### Web Frontend

Buka `web/index.html` langsung di browser, atau deploy ke Netlify.

Untuk menghubungkan ke Firebase, edit `FIREBASE_CONFIG` di `web/app.js` atau inject via environment variable `window.FIREBASE_CONFIG` pada build step.

---

## CI/CD

### Secrets yang dibutuhkan

| Secret | Deskripsi |
|--------|-----------|
| `KEYSTORE_BASE64` | Keystore Android (base64-encoded) |
| `KEYSTORE_PASSWORD` | Password keystore |
| `KEY_ALIAS` | Alias key signing |
| `KEY_PASSWORD` | Password key |
| `PLAY_STORE_SERVICE_ACCOUNT_JSON` | Service account JSON untuk Play Store (opsional) |
| `NETLIFY_AUTH_TOKEN` | Token autentikasi Netlify |
| `NETLIFY_SITE_ID` | ID site Netlify |

### Encode keystore

```bash
base64 -i keystore.jks | pbcopy   # macOS
base64 keystore.jks               # Linux
```

---

## Alur Pesanan

```
Pelanggan scan QR → Web Menu → Pilih item + topping → Checkout → Pilih pembayaran → Kirim pesanan
                                                                                          ↓
                                                               Notifikasi push ke Seller App
                                                                                          ↓
                                             Penjual konfirmasi → Antrian → Diproses → Siap → Disajikan
```

---

## Dokumentasi Fitur

### Multi-Customer Table Support
Sistem sekarang mendukung beberapa pelanggan memesan dari meja yang sama dengan riwayat pesanan yang terpisah untuk setiap pelanggan.

- 📖 [Multi-Customer Table Guide](MULTI_CUSTOMER_TABLE_GUIDE.md) – Panduan lengkap tentang fitur multi-pelanggan, cara kerja session ID, dan skenario penggunaan
- 🧪 [Testing Multi-Customer Sessions](TESTING_MULTI_CUSTOMER_SESSIONS.md) – Panduan pengujian komprehensif dengan 10+ skenario pengujian

### Setup dan Troubleshooting
- 📖 [Firebase Setup Guide](FIREBASE_SETUP_GUIDE.md) – Panduan setup Firebase project
- 📖 [Firestore Rules Guide](FIRESTORE_RULES_GUIDE.md) – Panduan security rules Firestore
- 🔧 [Android Fix Plan](ANDROID_FIX_PLAN.md) – Solusi masalah umum Android
- 🌐 [Web App Troubleshooting](WEB_APP_TROUBLESHOOTING.md) – Troubleshooting web app

---

## Lisensi

[MIT](LICENSE)
