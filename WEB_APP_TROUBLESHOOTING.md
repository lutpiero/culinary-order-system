# Web App Troubleshooting - "Habis" (Out of Stock) Issue

## Issue: Menu Items Show "Habis" and Cannot Be Added to Cart

When menu items display "Habis" (out of stock) in the web app, it means the `isAvailable` field is set to `false` in Firestore.

## Root Cause

The Android app has an "Tersedia" (Available) checkbox when creating/editing menu items. If this checkbox is **unchecked**, the item will be marked as unavailable and show "Habis" in the web app.

## How to Fix

### Step 1: Check Existing Menu Items in Firestore

1. Go to **Firebase Console** → **Firestore Database** → **Data** tab
2. Open the `menuItems` collection
3. Click on each menu item document
4. Check the `isAvailable` field value:
   - `true` = Available (can be added to cart)
   - `false` = Out of stock (shows "Habis")

### Step 2: Update Unavailable Items

**Option A: Via Firebase Console (Quick Fix)**
1. Click on the menu item document
2. Find the `isAvailable` field
3. Change value from `false` to `true`
4. Click **Update**
5. Refresh the web app - item should now be available

**Option B: Via Android App**
1. Open the Android app
2. Go to **Menu Management**
3. Tap the menu item you want to edit
4. **Check** the "Tersedia" checkbox ✅
5. Tap **Simpan Menu** (Save)
6. Refresh the web app - item should now be available

### Step 3: Create New Menu Items Correctly

When creating new menu items in the Android app:

1. Fill in all required fields:
   - **Nama Menu** (Name) *
   - **Harga** (Price) *
   - **Kategori** (Category)

2. **IMPORTANT:** Ensure the **"Tersedia"** checkbox is **CHECKED** ✅
   - This checkbox controls the `isAvailable` field
   - If unchecked, item will show as "Habis" in web app

3. Optional fields:
   - Deskripsi (Description)
   - URL Gambar (Image URL)
   - Waktu Persiapan (Preparation time)

4. Tap **Simpan Menu** (Save)

## Verification

After updating items:

1. **Check Firestore Console:**
   - Go to Firestore Database → menuItems collection
   - Verify `isAvailable: true` for all items

2. **Check Web App:**
   - Refresh the web app (https://culinary-order.netlify.app/?table=1)
   - Menu items should no longer show "Habis"
   - "+" button should appear instead
   - Items can be added to cart

## Understanding the Fields

### In Android App (AddEditMenuItemScreen.kt)
```kotlin
Row(verticalAlignment = Alignment.CenterVertically) {
    Checkbox(
        checked = item.isAvailable,  // ← Controls availability
        onCheckedChange = { viewModel.updateFormItem(item.copy(isAvailable = it)) }
    )
    Text("Tersedia")  // ← "Available" in Indonesian
}
```

### In Firestore
```json
{
  "id": "abc123",
  "name": "Nasi Goreng",
  "price": 25000,
  "isAvailable": true,  // ← Must be true for web app
  "categoryId": "cat1",
  "categoryName": "Makanan Utama"
}
```

### In Web App (app.js)
```javascript
const unavailableClass = !item.isAvailable ? " menu-card-unavailable" : "";
const actionHtml = item.isAvailable
  ? `<button class="menu-card-add" onclick="...">+</button>`
  : `<span class="menu-card-badge-unavailable">Habis</span>`;  // ← Shows "Habis"
```

## Common Scenarios

### Scenario 1: All Items Show "Habis"
**Cause:** Firestore security rules blocking reads

**Solution:** Update Firestore rules (see FIRESTORE_RULES_GUIDE.md)

### Scenario 2: Some Items Show "Habis"
**Cause:** Those specific items have `isAvailable: false`

**Solution:** Update those items via Firebase Console or Android app

### Scenario 3: New Items Always Show "Habis"
**Cause:** Forgetting to check "Tersedia" checkbox when creating

**Solution:** Always check the "Tersedia" checkbox when creating menu items

## Quick Checklist

Before testing the web app, ensure:

- [ ] Firestore security rules allow reads (see FIRESTORE_RULES_GUIDE.md)
- [ ] Menu items exist in Firestore `menuItems` collection
- [ ] All menu items have `isAvailable: true`
- [ ] Categories exist in Firestore `categories` collection
- [ ] Web app has proper Firebase config (see netlify.toml)

## Summary

**Problem:** Menu items show "Habis" (out of stock)

**Root Cause:** `isAvailable` field is `false` in Firestore

**Solution:** 
1. Check "Tersedia" checkbox when creating items in Android app
2. Or update `isAvailable: true` directly in Firebase Console

**Prevention:** Always verify the "Tersedia" checkbox is checked before saving menu items
