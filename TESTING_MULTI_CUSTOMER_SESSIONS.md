# Testing Multi-Customer Table Sharing

This guide provides comprehensive testing scenarios for the multi-customer table sharing feature.

## Prerequisites

- Culinary Order System running (web app and Android seller app)
- Firebase Firestore configured and accessible
- Multiple browsers or devices for testing concurrent sessions
- Browser developer tools for console inspection

## Test Scenarios

### Scenario 1: Verify Session ID Generation

**Objective:** Confirm that each customer gets a unique session ID

**Steps:**
1. Open browser Tab 1: Navigate to `http://localhost:5000/?table=1`
2. Open browser console (F12 or Cmd+Option+I)
3. Run: `console.log(state.sessionId)`
4. Note the sessionId (e.g., `abc123def456`)
5. Open browser Tab 2: Navigate to `http://localhost:5000/?table=1`
6. Run: `console.log(state.sessionId)` in Tab 2
7. **Verify:** Different sessionIds should be shown for Tab 1 and Tab 2

**Expected Result:**
```
Tab 1: abc123def456
Tab 2: xyz789uvw012
```

### Scenario 2: Session Persistence Within Tab

**Objective:** Confirm that sessionId persists when refreshing within the same tab

**Steps:**
1. Open Tab 1: Navigate to `http://localhost:5000/?table=1`
2. Note sessionId: `console.log(state.sessionId)` → returns `abc123def456`
3. Refresh the page (F5)
4. Check sessionId again: `console.log(state.sessionId)`
5. **Verify:** Same sessionId should be returned

**Expected Result:** Same sessionId persists after refresh

### Scenario 3: Different Tables = Different Sessions

**Objective:** Confirm that tables have independent session storage

**Steps:**
1. Open Tab 1: Navigate to `http://localhost:5000/?table=1`
2. Note sessionId for Table 1: `abc123`
3. Open Tab 2: Navigate to `http://localhost:5000/?table=2`
4. Note sessionId for Table 2: `xyz789`
5. Back to Tab 1: Refresh and check sessionId
6. **Verify:** Table 1 still has its original sessionId

**Expected Result:** Each table has independent sessionId storage

### Scenario 4: Two Customers at Same Table - Separate Order Histories

**Objective:** Verify that two customers ordering at the same table see only their own orders

**Steps:**

#### Setup:
1. **Browser A (Customer 1):** Open Table 1
   - `?table=1` → sessionId = `session_a`
   
2. **Browser B (Customer 2):** Open Table 1
   - `?table=1` → sessionId = `session_b`

#### Customer 1 Places Order:
1. In Browser A, add item to cart (e.g., Nasi Goreng)
2. Go to checkout
3. Enter name: "Budi"
4. Select payment method
5. Click "Pesan Sekarang" (Place Order)
6. Note order ID: `#abc123`
7. Click "Pesan Lagi" to go back

#### Customer 2 Places Order:
1. In Browser B, add item to cart (e.g., Mie Goreng)
2. Go to checkout
3. Enter name: "Siti"
4. Select payment method
5. Click "Pesan Sekarang"
6. Note order ID: `#xyz789`
7. Click "Pesan Lagi"

#### Verify Order Histories:
1. In Browser A, click order history (📋 icon)
   - **Should show:** Only "#abc123" (Nasi Goreng, Budi)
   - **Should NOT show:** "#xyz789" (Siti's order)
   
2. In Browser B, click order history
   - **Should show:** Only "#xyz789" (Mie Goreng, Siti)
   - **Should NOT show:** "#abc123" (Budi's order)

**Expected Result:** Each customer sees only their own order

### Scenario 5: Order Status Updates for Correct Session

**Objective:** Verify that order status updates only affect the correct session

**Steps:**

1. **Complete Scenario 4** (two orders at same table)
2. **In Android Seller App:**
   - Navigate to Orders screen
   - Find order "#abc123" (Budi's order)
   - Update status: PENDING → PREPARING
3. **In Browser A (Customer 1 - Budi):**
   - Order history should show status change to "Diproses"
4. **In Browser B (Customer 2 - Siti):**
   - Order history should still show "#xyz789" with original status
   - Should NOT be affected by Budi's status update

**Expected Result:** Status updates only affect the correct customer's order

### Scenario 6: Seller App Shows All Orders

**Objective:** Verify seller sees all orders regardless of sessionId

**Steps:**

1. **Complete Scenario 4** (two orders at same table)
2. **In Android Seller App:**
   - Navigate to Orders screen
   - **Verify:** Both "#abc123" and "#xyz789" appear in the orders list
   - Both should have tableNumber "1"
   - Both should have different customerNames (Budi, Siti)

**Expected Result:** Seller sees all orders from both sessions

### Scenario 7: URL-Based Session Sharing

**Objective:** Verify that customers can share a session via URL

**Steps:**

1. **Browser A (Customer 1):** Open Table 1
   - `?table=1` → sessionId = `abc123def456`
   
2. Copy the URL from the browser address bar and modify it:
   - `?table=1&session=abc123def456`
   
3. **Browser B (Customer 2):** Open the modified URL
   - `?table=1&session=abc123def456`
   
4. **Verify:** Both browsers have same sessionId
   - `console.log(state.sessionId)` returns `abc123def456` in both

5. **Browser A:** Place order "Order A"
6. **Browser B:** Check order history
   - **Should show:** "Order A" (same session)

7. **Browser B:** Place order "Order B"
8. **Browser A:** Check order history
   - **Should show:** Both "Order A" and "Order B"

**Expected Result:** Customers sharing a URL see the same order history

### Scenario 8: Concurrent Orders Under Load

**Objective:** Verify system handles multiple concurrent orders correctly

**Steps:**

1. Open 5 browser tabs with Table 1 (each gets unique sessionId)
2. In each tab:
   - Add 2 items to cart
   - Go to checkout
   - Enter unique customer name
   - Place order
3. **Verify:** All 5 orders created with correct sessionIds
4. Check each tab's order history
   - Each should see only 1 order (their own)
5. Check Android seller app
   - Should see all 5 orders from Table 1

**Expected Result:** All concurrent orders created correctly with proper attribution

### Scenario 9: Session Timeout Behavior

**Objective:** Verify behavior when sessionStorage is cleared

**Steps:**

1. Open Tab 1: Table 1 with sessionId = `abc123`
2. In developer console, run:
   ```javascript
   sessionStorage.clear()
   ```
3. Refresh page (F5)
4. Check sessionId:
   ```javascript
   console.log(state.sessionId)
   ```
5. **Verify:** New sessionId generated (not `abc123`)

**Note:** This is expected behavior - clearing sessionStorage simulates browser restart or incognito mode

### Scenario 10: Data Persistence in Firestore

**Objective:** Verify sessionId is correctly stored in Firestore

**Steps:**

1. Place an order in Table 1, Browser A (sessionId = `abc123`)
2. Open Firebase Console → Firestore → Collections → Orders
3. Find the order by order ID
4. **Verify:** Document has field `sessionId: "abc123"`
5. Open Table 1 in Browser B (sessionId = `xyz789`)
6. Place an order
7. In Firestore, verify the second order has `sessionId: "xyz789"`

**Expected Result:** sessionId field is properly stored in Firestore

## Test Checklist

- [ ] Session IDs are unique per browser tab
- [ ] Session IDs persist on page refresh
- [ ] Different tables have independent sessions
- [ ] Two customers at same table see separate order histories
- [ ] Order status updates affect correct session only
- [ ] Seller app shows all orders from all sessions
- [ ] Customers can share sessions via URL
- [ ] System handles concurrent orders correctly
- [ ] Firestore stores sessionId correctly
- [ ] Session IDs are properly formatted (not null/empty)

## Browser Console Debugging

### Check Current State
```javascript
console.log({
  tableNumber: state.tableNumber,
  sessionId: state.sessionId,
  orderCount: state.orders.length
})
```

### Verify Order Query
```javascript
// Check what sessionId filter would retrieve
console.log(`Filtering for orders: tableNumber="${state.tableNumber}" AND sessionId="${state.sessionId}"`)
```

### Check SessionStorage
```javascript
// View all sessionStorage entries
Object.entries(sessionStorage).forEach(([key, value]) => {
  console.log(`${key}: ${value}`)
})
```

## Firebase Console Verification

### View Orders by Session
1. Go to Firebase Console → Firestore
2. Click Collections → Orders
3. For each order, verify:
   - `tableNumber` matches expected table
   - `sessionId` matches expected session
   - `customerName` is correctly set
   - `createdAt` timestamp is recent

### Test Query
1. Go to Firestore → Orders collection
2. Use the query builder to test:
   ```
   tableNumber == "1" AND sessionId == "abc123def456"
   ```
3. Verify only orders from that session appear

## Performance Testing

### Test with Multiple Sessions
1. Create 10+ concurrent sessions on Table 1
2. Each places an order simultaneously
3. **Measure:**
   - Time to retrieve order history (should be <1 second)
   - Firestore read usage (composite index should be efficient)
   - No errors in console

## Common Issues & Solutions

### Issue: All Browsers Show Same SessionId
**Cause:** Using localStorage instead of sessionStorage
**Solution:** Check that sessionStorage is used, not localStorage

### Issue: SessionId Empty/Null
**Cause:** generateId() function not available
**Solution:** Verify generateId() function exists in app.js

### Issue: Orders Query Returns All Orders
**Cause:** Query missing sessionId filter
**Solution:** Verify loadOrderHistory has both tableNumber AND sessionId filters

### Issue: Firestore Composite Index Error
**Cause:** Query requires index that doesn't exist
**Solution:** Firebase will prompt to create index - click the link to create it

## Automated Testing (Recommended)

For CI/CD pipeline, consider adding tests for:

```javascript
// Test sessionId generation
test('generates unique sessionIds', () => {
  const id1 = generateId()
  const id2 = generateId()
  expect(id1).not.toBe(id2)
})

// Test sessionStorage persistence
test('persists sessionId in sessionStorage', () => {
  parseTableFromUrl()
  const stored = sessionStorage.getItem('culinary_session_table_1')
  expect(stored).toBe(state.sessionId)
})

// Test query filter
test('query includes sessionId filter', () => {
  // Verify loadOrderHistory query structure
  // Mock Firestore and verify where() clauses
})
```

## Conclusion

The multi-customer table sharing feature allows multiple customers to order from the same physical table while maintaining independent order histories. This testing guide ensures the feature works correctly across all scenarios.

For any test failures, refer to the troubleshooting section or check the MULTI_CUSTOMER_TABLE_GUIDE.md documentation.
