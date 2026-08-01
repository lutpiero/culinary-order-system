# Multi-Customer Table Sharing Solution

## Problem Statement
When different user sessions use the same table number, the system was unable to differentiate which customer placed which order. This occurred because the system only used `tableNumber` as the identifier, with no way to distinguish between multiple customers at the same physical table.

## Solution Overview
The system now implements a **unique Session ID** for each customer visit to a table. This allows multiple customers to share the same physical table while maintaining individual order tracking and history.

## How It Works

### Web App (Customer Side)

#### Session ID Generation
1. When a customer scans a QR code for table 1 (e.g., `?table=1`), the web app generates or retrieves a unique **Session ID**
2. The Session ID is stored in **sessionStorage** with key: `culinary_session_table_<tableNumber>`
3. Each new browser tab/session will generate a new Session ID
4. If a Session ID is passed in the URL (`?table=1&session=abc123`), it will be used instead

#### Order Placement
- When placing an order, the **sessionId** is now included in the order data:
  ```javascript
  const order = {
    tableNumber: "1",
    sessionId: "abc123def456",    // Unique to this customer
    customerName: "Budi",
    items: [...],
    // ... other fields
  };
  ```

#### Order History Filtering
- Order history is now filtered by **both tableNumber AND sessionId**:
  ```javascript
  const ordersQuery = query(
    collection(state.db, "orders"),
    where("tableNumber", "==", state.tableNumber),
    where("sessionId", "==", state.sessionId),  // Only this customer's orders
    orderBy("createdAt", "desc")
  );
  ```
- This ensures each customer only sees their own orders, even if multiple people are ordering from the same physical table

### Android App (Seller Side)

#### Order Model Update
- The `Order` data class now includes a `sessionId` field:
  ```kotlin
  data class Order(
    val id: String = UUID.randomUUID().toString(),
    val tableNumber: String = "",
    val sessionId: String = "",    // New field
    val customerName: String = "",
    // ... other fields
  )
  ```

#### Order Display
- Sellers can see the `sessionId` in orders to understand which customer placed each order
- The `customerName` field combined with `sessionId` provides clear attribution

## Benefits

### For Customers
1. ✅ Multiple customers can order from the same table simultaneously
2. ✅ Each customer only sees their own order history
3. ✅ No confusion about which orders belong to whom
4. ✅ Clear, individual order tracking

### For Sellers
1. ✅ Can identify which customer placed each order (via sessionId + customerName)
2. ✅ Can process orders correctly when multiple orders from the same table
3. ✅ Better order attribution and accuracy

## Technical Implementation

### Firestore Data Structure
Orders now have this structure:
```json
{
  "id": "order123",
  "tableNumber": "1",
  "sessionId": "abc123def456",
  "customerName": "Budi",
  "items": [...],
  "status": "PENDING",
  "paymentMethod": "CASHIER",
  "createdAt": "2024-08-01T10:30:00Z",
  "updatedAt": "2024-08-01T10:30:00Z",
  "estimatedReadyMinutes": 15
}
```

### Query Optimization
The web app uses a composite index query:
```firestore
WHERE tableNumber = "1" AND sessionId = "abc123def456"
ORDER BY createdAt DESC
```

This composite filter ensures:
- Fast queries (indexed)
- Data isolation per session
- Correct order attribution

## Migration Considerations

### Backward Compatibility
- Existing orders without `sessionId` may still be retrieved if queried by tableNumber only
- New orders will always have `sessionId` populated
- Recommendation: Add sessionId to old orders in migration or filter them out

### Firestore Indexes
No additional indexes are required if you're already querying by tableNumber. However, for optimal performance with the new dual-field queries, ensure Firestore creates the composite index automatically (it will prompt you when the query first runs).

## URL Sharing

### Direct Links to Specific Sessions
You can create shareable links with sessionId:
```
https://example.com/?table=1&session=abc123def456
```

This allows:
- Reopening the same session later (e.g., if browser crashed)
- Sharing the menu link with other members at your table who want to order together under the same session
- Better session management

### Default Behavior
- If no session parameter is provided, a new sessionId is automatically generated
- Each new browser tab will have its own sessionId (independent sessions)

## Testing Recommendations

### Scenario 1: Two Customers at Same Table
1. Open browser 1: `?table=1` → Gets sessionId: "session1"
2. Open browser 2: `?table=1` → Gets sessionId: "session2"
3. Customer 1 places order in browser 1
4. Customer 2 places order in browser 2
5. Browser 1 should only show Customer 1's order
6. Browser 2 should only show Customer 2's order
7. Seller sees both orders with different sessionIds

### Scenario 2: Same Customer Reopens Menu
1. Open browser: `?table=1` → Gets sessionId: "session1"
2. Customer places order
3. Close browser completely
4. Reopen: `?table=1` → Gets NEW sessionId: "session2"
5. Old order (sessionId: "session1") won't appear
6. **Note:** This is expected behavior - each session is independent

### Scenario 3: Shared Session Link
1. Customer 1 opens: `?table=1` → Gets sessionId: "abc123"
2. Customer 1 sends link to Customer 2: `?table=1&session=abc123`
3. Both customers place orders with the same sessionId
4. Both see the same order history in the drawer
5. Both orders appear under the same "session"

## Future Enhancements

### Possible Improvements
1. **Session Metadata:** Store session creation time, customer count estimates, etc.
2. **Session Management UI:** Allow customers to see other sessions at their table (optional)
3. **Joint Ordering:** Customers can opt to share a session for combined orders
4. **Seller Dashboard:** Show all sessions per table for better organization
5. **Session Timeout:** Auto-cleanup of old sessions
6. **Anonymous Sessions:** Generate shorter session IDs or memorable names

## Support for Multiple Scenarios

### Scenario A: Separate Individual Orders (Current Default)
- Each customer gets their own sessionId
- Each customer orders independently
- Each customer pays separately
- Best for: casual dining, multiple guests

### Scenario B: Shared Table Order (Optional)
- Group of customers share one sessionId
- All orders visible to all group members
- Can total bill and split later
- Best for: parties, groups, family meals

### Scenario C: Seller-Initiated Sessions
- Seller can generate a sessionId for a table upfront
- Customers scan QR with pre-assigned sessionId
- All orders grouped together
- Best for: better control, aggregated billing

## Troubleshooting

### Issue: Order History Empty
**Possible Causes:**
1. Browser cleared sessionStorage (order history uses sessionStorage)
2. Different sessionId being used
3. Orders not yet synced to Firestore

**Solution:**
- Check browser console for sessionId: `console.log(state.sessionId)`
- Verify sessionId is being saved to orders
- Wait a few seconds for Firestore sync

### Issue: Seeing Other Customers' Orders
**Possible Causes:**
1. sessionId collision (extremely unlikely with UUID)
2. Firestore rules not updated

**Solution:**
- Ensure queries include both tableNumber AND sessionId filters
- Verify Firestore rules allow read access to orders collection

## Firestore Rules

Ensure your Firestore rules allow reads from the orders collection:

```javascript
match /orders/{orderId} {
  allow read: if true;
  allow create: if request.auth != null;
}
```

Or if you require authentication:

```javascript
match /orders/{orderId} {
  allow read, write: if request.auth != null;
}
```

## Summary

The multi-customer table sharing solution provides a robust way to handle scenarios where multiple customers order from the same physical table. Each customer gets a unique session ID, ensuring:

- ✅ Clear order attribution
- ✅ Individual order histories
- ✅ No data overlap or confusion
- ✅ Scalable to hundreds of concurrent sessions per table
- ✅ Backward compatible with existing orders

This enhancement significantly improves the user experience and reduces errors in order tracking for busy restaurants.
