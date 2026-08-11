# Firebase Setup Guide - Local Development

## Issue Identified ❌
The Android app is **not syncing data to Firebase** when built locally because the `google-services.json` file is missing from your local `app/` directory.

## Current Status

✅ **CI/CD (GitHub Actions):** Already configured correctly
- Workflow creates `google-services.json` from `GOOGLE_SERVICES_JSON` secret
- Builds work fine in GitHub Actions
- Release APKs/AABs have proper Firebase config

❌ **Local Development:** Missing configuration
- No `google-services.json` in local `app/` directory
- App runs but saves data locally only (offline mode)
- Firebase SDK cannot connect to cloud

## Why You Need It Locally

When you build the app on your local machine (not via GitHub Actions), Gradle needs `app/google-services.json` to:
- Configure Firebase SDK with your project details
- Enable cloud sync to Firestore
- Enable push notifications via FCM
- Connect to Firebase Authentication

**Without it:** App works in offline mode only (local storage).

## How to Fix - Local Development

### Step 1: Download google-services.json

1. Go to [Firebase Console](https://console.firebase.google.com/)
2. Select your project
3. Click the gear icon (⚙️) → **Project settings**
4. Scroll to **Your apps** section
5. Find your Android app (package: `com.culinary.orderapp`)
6. Click **Download google-services.json**

### Step 2: Add to Local Project

Place the downloaded file in your local `app/` directory:

```
culinary-order-system/
├── app/
│   ├── google-services.json  ← Add here (for local dev)
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/
```

**File path:** `/home/lutfi/culinary-order-system/app/google-services.json`

### Step 3: Verify File Content

Open the file and verify it contains your project configuration:
```json
{
  "project_info": {
    "project_number": "123456789012",
    "project_id": "your-project-id",
    ...
  },
  "client": [
    {
      "client_info": {
        "mobilesdk_app_id": "1:123456789012:android:...",
        "android_client_info": {
          "package_name": "com.culinary.orderapp"
        }
      },
      ...
    }
  ]
}
```

### Step 4: Rebuild and Test Locally

1. **Clean and rebuild:**
   ```bash
   ./gradlew clean
   ./gradlew assembleDebug
   ```

2. **Install on device:**
   ```bash
   ./gradlew installDebug
   ```

3. **Test data sync:**
   - Open the app on your device
   - Create a menu item or order
   - Check Firebase Console → Firestore Database
   - Data should now appear in the cloud ✅

## Verification Checklist

After adding the file locally, verify:

- [ ] File exists at `app/google-services.json` in your local project
- [ ] File contains your project's configuration (check `project_id` matches)
- [ ] App rebuilds without errors
- [ ] Check Logcat for: `Firebase initialized successfully`
- [ ] Create test data in the app
- [ ] Data appears in Firebase Console → Firestore Database

## Important Notes

### File Security
- ✅ File is in `.gitignore` (won't be committed to Git)
- ✅ GitHub Actions uses secret for CI/CD builds
- ✅ Safe to have locally for development

### CI/CD vs Local Development

| Environment | google-services.json Source |
|-------------|----------------------------|
| **GitHub Actions** | Created from `GOOGLE_SERVICES_JSON` secret ✅ |
| **Local Development** | Must download from Firebase Console ❌ |

### Common Issues

#### Issue 1: Wrong Package Name
**Error:** `No matching client found for package name 'com.culinary.orderapp'`

**Solution:** 
- In Firebase Console, verify Android app package name is `com.culinary.orderapp`
- If not, add a new Android app with correct package name

#### Issue 2: Firestore Security Rules Blocking Writes
**Error:** Data not saving even with google-services.json

**Solution:** Check Firestore rules in Firebase Console:
```javascript
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {
    // Temporary: Allow all reads/writes for testing
    match /{document=**} {
      allow read, write: if true;
    }
  }
}
```

**⚠️ Important:** Change to proper authentication rules before production!

#### Issue 3: No Internet Permission
**Error:** Network errors in Logcat

**Solution:** Verify `AndroidManifest.xml` has:
```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

#### Issue 4: Menu Image Upload Fails (Cloudinary)
**Error:** Uploading a menu image or the company logo fails with "Object does not exist at location" (or any upload error).

> The app **no longer uses Firebase Storage**. Images are uploaded to **Cloudinary** (free plan, no credit card) and the returned `secure_url` is stored in Firestore (`menuItems/<id>.imageUrl` and `settings.logoUrl`). The web app renders those URLs as-is.

**Setup (one time, ~10 minutes):**
1. Create a free Cloudinary account → note your **Cloud Name**.
2. In Console → **Settings → Upload → Upload presets → Add upload preset**:
   - Name it e.g. `menu_images` and mark it **unsigned**.
   - Recommended hardening: `allowed_formats` = `jpg,jpeg,png,webp`, `max_file_size` = `10485760` (10 MB).
3. Configure the Android build (in `gradle.properties` or `~/.gradle/gradle.properties`):
   ```
   CLOUDINARY_CLOUD_NAME=your-cloud-name
   CLOUDINARY_UPLOAD_PRESET=your-unsigned-preset
   ```
   These are read as `BuildConfig` fields (`app/build.gradle.kts`). Rebuild the app afterwards.
4. For image deletion (orphan cleanup when a menu item or its image is removed), deploy the included `delete-image` Netlify function with these env vars:
   ```
   CLOUDINARY_CLOUD_NAME
   CLOUDINARY_API_KEY
   CLOUDINARY_API_SECRET
   ```
   and set `NETLIFY_FUNCTIONS_BASE_URL` (e.g. `https://your-site.netlify.app`) in the Android build config. If it's left blank, deleting an item still works but the old image file is left in Cloudinary (it only costs free-plan storage credits).

**Checklist:**
- [ ] Cloudinary cloud name + unsigned upload preset created
- [ ] `CLOUDINARY_CLOUD_NAME` / `CLOUDINARY_UPLOAD_PRESET` set in gradle properties, app rebuilt
- [ ] Logcat shows `Image uploaded successfully` (or `Cloudinary upload failed`) from `StorageRepository`
- [ ] Optional: `delete-image` function deployed + `NETLIFY_FUNCTIONS_BASE_URL` set for cleanup

## Summary

- ✅ **CI/CD:** Already configured with GitHub secrets
- ❌ **Local Dev:** Need to download and add `google-services.json`
- 📍 **Location:** `app/google-services.json` in your local project
- 🔒 **Security:** File is gitignored, safe for local development

**Download the file from Firebase Console and add it to your local `app/` directory to enable cloud sync!**