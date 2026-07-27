# Firestore Field Name Fix - isAvailable vs available

## Issue Fixed ✅

**Problem:** Firestore was storing Boolean fields without the "is" prefix, causing a mismatch between Android app and web app.

**Example:**
- Android code: `isAvailable: Boolean`
- Firestore stored: `available: true` (stripped "is" prefix)
- Web app expected: `isAvailable: true`
- Result: Web app couldn't read availability status correctly

## Root Cause

Firestore's Kotlin SDK automatically strips the "is" prefix from Boolean property names following JavaBean conventions. This caused:

```kotlin
// In code
data class MenuItemDto(
    val isAvailable: Boolean = true
)

// In Firestore database
{
  "available": true  // ❌ "is" prefix removed
}
```

## Solution Applied

Added `@PropertyName` annotations to force Firestore to preserve the full field names:

```kotlin
import com.google.firebase.firestore.PropertyName

data class MenuItemDto(
    @PropertyName("isAvailable")  // ✅ Forces Firestore to use "isAvailable"
    val isAvailable: Boolean = true
)
```

## Files Modified

**File:** `app/src/main/kotlin/com/culinary/orderapp/data/model/MenuDtos.kt`

**Changes:**
1. Added import: `com.google.firebase.firestore.PropertyName`
2. Added `@PropertyName("isActive")` to `CategoryDto.isActive`
3. Added `@PropertyName("isRequired")` to `ToppingDto.isRequired`
4. Added `@PropertyName("isAvailable")` to `ToppingDto.isAvailable`
5. Added `@PropertyName("isRequired")` to `ToppingGroupDto.isRequired`
6. Added `@PropertyName("isAvailable")` to `MenuItemDto.isAvailable`

## Required User Actions

### Step 1: Rebuild the App

The app must be rebuilt to apply the `@PropertyName` annotations:

**Via GitHub Actions (Recommended):**
```bash
git add app/src/main/kotlin/com/culinary/orderapp/data/model/MenuDtos.kt
git commit -m "Fix Firestore field names with @PropertyName annotations"
git push
```

GitHub Actions will automatically build the APK with the fix.

### Step 2: Handle Existing Data

You have two options for existing menu items in Firestore:

**Option A: Delete and Recreate (Simplest)**
1. Go to Firebase Console → Firestore Database
2. Delete all documents in `menuItems` collection
3. Open the rebuilt Android app
4. Create menu items again (they will now use `isAvailable`)
5. **Remember to check "Tersedia" checkbox** ✅

**Option B: Update Field Names (Preserve Data)**
1. Go to Firebase Console → Firestore Database
2. For each document in `menuItems` collection:
   - Click the document
   - Find the `available` field
   - Click the field name to edit
   - Rename from `available` to `isAvailable`
   - Click Update
3. Repeat for all menu items

### Step 3: Verify the Fix

After rebuilding and handling existing data:

1. **Check Firestore Console:**
   - Go to Firestore Database → menuItems collection
   - Open a menu item document
   - Verify field is named `isAvailable` (not `available`)

2. **Test Web App:**
   - Visit: https://culinary-order.netlify.app/?table=1
   - Menu items should display correctly
   - Items with `isAvailable: true` should show "+" button
   - Items with `isAvailable: false` should show "Habis"

3. **Test Android App:**
   - Create a new menu item
   - Check "Tersedia" checkbox
   - Save the item
   - Verify in Firestore that field is `isAvailable: true`

## Technical Details

### Before Fix

**Android DTO:**
```kotlin
data class MenuItemDto(
    val isAvailable: Boolean = true  // No annotation
)
```

**Firestore Storage:**
```json
{
  "name": "Nasi Goreng",
  "available": true  // ❌ Wrong field name
}
```

**Web App:**
```javascript
if (item.isAvailable) {  // ❌ undefined, reads wrong field
  // Show add button
}
```

### After Fix

**Android DTO:**
```kotlin
data class MenuItemDto(
    @PropertyName("isAvailable")  // ✅ Annotation added
    val isAvailable: Boolean = true
)
```

**Firestore Storage:**
```json
{
  "name": "Nasi Goreng",
  "isAvailable": true  // ✅ Correct field name
}
```

**Web App:**
```javascript
if (item.isAvailable) {  // ✅ Works correctly
  // Show add button
}
```

## Affected Fields

All Boolean fields with "is" prefix now have `@PropertyName` annotations:

| DTO | Field | Annotation |
|-----|-------|------------|
| CategoryDto | isActive | @PropertyName("isActive") |
| ToppingDto | isRequired | @PropertyName("isRequired") |
| ToppingDto | isAvailable | @PropertyName("isAvailable") |
| ToppingGroupDto | isRequired | @PropertyName("isRequired") |
| MenuItemDto | isAvailable | @PropertyName("isAvailable") |

## Summary

✅ **Fixed:** Added `@PropertyName` annotations to preserve Boolean field names
✅ **Impact:** Web app can now correctly read availability status
✅ **Action Required:** Rebuild app and handle existing Firestore data
✅ **Prevention:** All future Boolean fields will use correct names

**After completing the user actions, both Android and web apps will work correctly!**
