# Firestore Security Rules Guide

## Issue: Data Not Syncing to Firebase Cloud

Since your app is built via GitHub Actions with proper `google-services.json` configuration, the most likely cause is **Firestore Security Rules blocking writes**.

## How to Check Firestore Rules

### Step 1: Open Firebase Console
1. Go to [Firebase Console](https://console.firebase.google.com/)
2. Select your project
3. Click **Firestore Database** in the left menu
4. Click the **Rules** tab at the top

### Step 2: Check Current Rules

You'll see something like this:

**Option A: Production Mode (Blocks Everything)**
```javascript
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {
    match /{document=**} {
      allow read, write: if false;  // ❌ Blocks all access
    }
  }
}
```

**Option B: Test Mode (Allows Everything - Temporary)**
```javascript
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {
    match /{document=**} {
      allow read, write: if request.time < timestamp.date(2024, 12, 31);  // ⚠️ Expires
    }
  }
}
```

## Fix: Update Security Rules

### For Testing (Temporary - Allow All Access)

**⚠️ WARNING: Only use for testing! Not secure for production!**

```javascript
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {
    match /{document=**} {
      allow read, write: if true;  // ✅ Allows all access (testing only)
    }
  }
}
```

**Steps:**
1. Copy the rules above
2. Paste in the Rules editor
3. Click **Publish**
4. Test your app - data should now sync to Firebase

### For Production (Secure - Recommended)

Based on your app's collections, use these rules:

```javascript
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {
    
    // Menu Items - Anyone can read, only authenticated users can write
    match /menuItems/{itemId} {
      allow read: if true;
      allow write: if request.auth != null;
    }
    
    // Categories - Anyone can read, only authenticated users can write
    match /categories/{categoryId} {
      allow read: if true;
      allow write: if request.auth != null;
    }
    
    // Orders - Anyone can read, only authenticated users can write
    match /orders/{orderId} {
      allow read: if true;
      allow write: if request.auth != null;
    }
    
    // If you add user authentication later, use this pattern:
    // match /orders/{orderId} {
    //   allow read: if request.auth != null;
    //   allow create: if request.auth != null;
    //   allow update, delete: if request.auth.uid == resource.data.userId;
    // }
  }
}
```

**Steps:**
1. Copy the rules above
2. Paste in the Rules editor
3. Click **Publish**
4. Test your app

## Verify the Fix

After updating rules:

1. **Open your app** on the device
2. **Create a menu item or order**
3. **Check Firebase Console** → Firestore Database → Data tab
4. **Look for collections:** `menuItems`, `categories`, `orders`
5. **Verify data appears** in the cloud

## Common Rule Patterns

### Pattern 1: Public Read, Authenticated Write
```javascript
allow read: if true;
allow write: if request.auth != null;
```
- Anyone can read data
- Only logged-in users can write
- Good for menu items, categories

### Pattern 2: Authenticated Only
```javascript
allow read, write: if request.auth != null;
```
- Only logged-in users can read/write
- Good for user-specific data

### Pattern 3: Owner Only
```javascript
allow read, write: if request.auth.uid == resource.data.userId;
```
- Only the owner can access their data
- Good for private user data

### Pattern 4: Public (Testing Only)
```javascript
allow read, write: if true;
```
- ⚠️ Anyone can read/write
- Only for testing/development
- Never use in production

## Troubleshooting

### Issue: Still Not Syncing After Rule Update

**Check 1: Internet Connectivity**
- Ensure device has internet access
- Try opening a website in the browser

**Check 2: Logcat Errors**
- Open Android Studio
- Connect device
- View Logcat
- Look for errors containing:
  - `PERMISSION_DENIED`
  - `FirebaseFirestore`
  - `Failed to write`

**Check 3: Firebase Initialization**
- Check Logcat for: `Firebase initialized successfully`
- If missing, there's an initialization issue

**Check 4: App Permissions**
- Verify `AndroidManifest.xml` has:
  ```xml
  <uses-permission android:name="android.permission.INTERNET" />
  <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
  ```

### Issue: Rules Published But Not Working

**Solution:**
1. Wait 1-2 minutes for rules to propagate
2. Force close the app completely
3. Reopen the app
4. Try creating data again

## Next Steps

1. ✅ Update Firestore rules (use testing rules first)
2. ✅ Publish the rules
3. ✅ Test the app
4. ✅ Verify data appears in Firebase Console
5. ✅ Once working, update to production-ready rules

## Security Best Practices

1. **Never use `allow read, write: if true;` in production**
2. **Always require authentication for writes**
3. **Validate data structure in rules**
4. **Use field-level security when needed**
5. **Test rules thoroughly before deploying**

## Example: Complete Secure Rules

```javascript
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {
    
    // Helper function to check if user is authenticated
    function isAuthenticated() {
      return request.auth != null;
    }
    
    // Helper function to validate menu item data
    function isValidMenuItem() {
      return request.resource.data.keys().hasAll(['name', 'price', 'category'])
        && request.resource.data.name is string
        && request.resource.data.price is number
        && request.resource.data.price > 0;
    }
    
    // Menu Items
    match /menuItems/{itemId} {
      allow read: if true;
      allow create: if isAuthenticated() && isValidMenuItem();
      allow update: if isAuthenticated() && isValidMenuItem();
      allow delete: if isAuthenticated();
    }
    
    // Categories
    match /categories/{categoryId} {
      allow read: if true;
      allow write: if isAuthenticated();
    }
    
    // Orders
    match /orders/{orderId} {
      allow read: if true;
      allow create: if isAuthenticated();
      allow update: if isAuthenticated();
      allow delete: if isAuthenticated();
    }
  }
}
```

## Summary

**Most Common Issue:** Firestore rules blocking writes

**Quick Fix for Testing:**
```javascript
allow read, write: if true;
```

**Production Fix:**
```javascript
allow read: if true;
allow write: if request.auth != null;
```

**After fixing rules, your app should sync data to Firebase Cloud!**
