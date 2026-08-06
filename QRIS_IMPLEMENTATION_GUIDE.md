# QRIS Payment System Implementation Guide

## Overview

This document describes the QRIS (Quick Response Code Indonesian Standard) payment implementation for the Culinary Order System. The system now supports generating QRIS QR codes for payment processing and tracking payment status through polling.

## Architecture

### Components

1. **QRIS Utility (`web/qris-util.js`)**
   - Generates QRIS payment strings based on amount and reference number
   - Calculates CRC-16 CCITT checksum for QRIS validation
   - Converts QRIS string to QR code image

2. **Web App Frontend (`web/app.js`)**
   - Displays QRIS QR code after order placement
   - Implements payment status polling
   - Handles payment confirmation

3. **Data Models**
   - Extended Order model with payment tracking fields
   - PaymentStatus enum for tracking payment lifecycle

## How QRIS Payment Works

### 1. Order Placement with QRIS

When a customer selects QRIS as the payment method:

```
Customer → Selects QRIS → Places Order → QR Code Generated → Polling Starts
```

### 2. QRIS QR Code Generation

The QRIS string is generated using the following format:

```
[Prefix] + [Amount TLV] + [Middle] + [Additional Data TLV] + [Suffix] + [CRC]

Where:
- Prefix: Fixed QRIS header from acquirer
- Amount TLV: Tag-Length-Value format for transaction amount
- Middle: Fixed merchant/bank data
- Additional Data TLV: Reference number and terminal info
- Suffix: Fixed suffix
- CRC: CRC-16 CCITT checksum
```

Example:
```javascript
const qrisPayment = generateQrisPayment(10000, "ORD123");
// Returns: {
//   qrisString: "00020101021226710019...",
//   qrCodeImage: "data:image/png;base64,...",
//   amount: 10000,
//   referenceNumber: "ORD123"
// }
```

### 3. Payment Status Polling

After displaying the QR code, the web app polls the backend every 3 seconds to check if payment has been completed:

```javascript
// Polling interval: 3 seconds
// Max polling time: 10 minutes
// Checks order's paymentStatus field in Firestore
```

The polling stops when:
- Payment is confirmed (paymentStatus === "PAID")
- Max polling time is reached
- User closes the page

### 4. Payment Confirmation

When payment is confirmed:
- Order status updates to reflect payment completion
- Seller receives notification
- Customer sees payment confirmation message

## Implementation Details

### QRIS String Format

The QRIS string follows BI (Bank Indonesia) QRIS specification:

- **Tag 54 (Amount)**: Transaction amount in IDR
- **Tag 62 (Additional Data)**:
  - Sub-tag 05: Reference number (order ID, max 25 chars)
  - Sub-tag 07: Terminal information

### CRC-16 Calculation

```javascript
function getQrisCrc(data) {
  let crc = 0xFFFF;
  for (let i = 0; i < data.length; i++) {
    crc ^= (data.charCodeAt(i) << 8);
    for (let j = 0; j < 8; j++) {
      if (crc & 0x8000) {
        crc = ((crc << 1) ^ 0x1021) & 0xFFFF;
      } else {
        crc = (crc << 1) & 0xFFFF;
      }
    }
  }
  return crc.toString(16).toUpperCase().padStart(4, '0');
}
```

### Order Model Updates

New fields in Order model:

```kotlin
data class Order(
    // ... existing fields ...
    val paymentStatus: PaymentStatus = PaymentStatus.PENDING,  // PENDING, PAID, FAILED, CANCELLED
    val paymentRefNo: String = "",  // Reference number for QRIS payment
    // ... other fields ...
)

enum class PaymentStatus(val displayName: String) {
    PENDING("Menunggu Pembayaran"),
    PAID("Sudah Dibayar"),
    FAILED("Pembayaran Gagal"),
    CANCELLED("Pembayaran Dibatalkan")
}
```

## Web App Flow

### Success Page with QRIS

When QRIS payment is selected:

1. Order created with `paymentStatus = "PENDING"`
2. QRIS QR code generated and displayed
3. Polling starts automatically
4. User scans QR with e-wallet app and completes payment
5. Backend receives payment notification
6. Order's `paymentStatus` updated to "PAID"
7. Polling detects change and confirms payment to user

### UI Elements

**QRIS Payment Section** (shown when payment method = QRIS):

```html
<div id="qrisPaymentSection">
  <div class="qris-container">
    <h3>Pembayaran via QRIS</h3>
    <div class="qris-status">Menunggu pembayaran...</div>
    <div class="qris-qr-wrapper">
      <img id="qrisQrCode" alt="QR Code QRIS" />
      <p>Scan dengan aplikasi e-wallet Anda</p>
    </div>
    <div id="qrisPollingStatus">
      <span class="spinner-small"></span>
      <p>Memeriksa status pembayaran...</p>
    </div>
  </div>
</div>
```

## Firebase Firestore Structure

### Orders Collection

```json
{
  "id": "order_123",
  "tableNumber": "1",
  "sessionId": "session_456",
  "customerName": "John Doe",
  "items": [...],
  "status": "PENDING",
  "paymentMethod": "QRIS",
  "paymentStatus": "PENDING",
  "paymentRefNo": "order_123",
  "createdAt": "2024-08-05T10:30:00Z",
  "updatedAt": "2024-08-05T10:30:00Z"
}
```

### QRIS Transactions Collection (Optional)

For additional tracking and audit trail:

```json
{
  "orderId": "order_123",
  "qrisString": "00020101021226...",
  "amount": 10000,
  "status": "PENDING",
  "createdAt": "2024-08-05T10:30:00Z",
  "updatedAt": "2024-08-05T10:30:00Z"
}
```

## Backend Integration Points (Implemented)

Since the DSP Pratama QRIS gateway (`https://remoteqr.dspratama.co.id`) has no webhook and DSP transactions don't reliably carry a partner reference number, payment confirmation works by **polling DSP and matching transactions to orders by exact amount**. To make amount-matching unambiguous, every QRIS order reserves a unique amount for a limited time before generating its QR code.

### Amount Locking

- Collection `qrisAmountLocks/{amount}` (doc ID = the amount itself): `{ amount, orderId, tableNumber, status: LOCKED|RELEASED, lockedAt, expiresAt }`.
- When a customer checks out with QRIS, the client (`web/app.js: reserveQrisAmount()`) atomically tries `baseAmount`, then `baseAmount + feeAmount`, `+2*feeAmount`, ... (Firestore transaction against `qrisAmountLocks/{candidate}`) until it claims an amount that's unlocked or whose lock has expired.
- The order stores `qrisBaseAmount`, `qrisAmount` (the amount actually encoded in the QR), `qrisFeeSteps`, and `paymentLockExpiresAt`.
- The lock is held for `QRIS_CONFIG.lockDurationMs` (default 5 minutes). After it expires, the amount becomes reusable by new orders — but the original order **stays `PENDING`** so a late payment can still be matched by amount as long as nobody else has reused it since.
- `feeAmount` (default Rp 1) and `lockDurationMs` are configured in `web/config.js` under `window.QRIS_CONFIG`.

### DSP Transaction Checking (Netlify Functions)

Credentials (`DS_APP_BASE_URL`, `DS_APP_USERNAME`, `DS_APP_PASSWORD`, `DS_APP_MID`) and Firestore Admin credentials (`FIREBASE_SERVICE_ACCOUNT_JSON`) live only in **Netlify site environment variables** — never in client code.

- `netlify/functions/lib/dsp-client.js` — logs into DSP (`POST /login`), caches the session cookie, and fetches `GET /trx-qr?fromDate&toDate&mid`, parsing the HTML-embedded `reportData` JSON payload.
- `netlify/functions/lib/reconcile.js` — for each `APPROVED` transaction not already processed (deduped via `qrisProcessedTransactions/{transactionId}`), finds the oldest `orders` doc with matching `paymentMethod=QRIS`, `paymentStatus=PENDING`, `qrisAmount=AMOUNT`, marks it `PAID`, and releases its amount lock. Also sweeps expired locks (`status=LOCKED && expiresAt < now` -> `RELEASED`).
- `netlify/functions/scheduled-check-dsp.js` — Netlify **Scheduled Function** (cron `* * * * *`, i.e. every minute — Netlify's minimum granularity) that scans the last 15 minutes of DSP transactions and reconciles them. This is the reliability safety net.
- `netlify/functions/check-payment-now.js` — on-demand HTTP function called by the client's existing 3s payment-status polling loop to trigger a faster DSP check. Actual DSP calls are throttled globally (via a `system/dspPollThrottle` Firestore doc) to roughly once every 7 seconds regardless of how many customers are polling simultaneously, so many concurrent customers don't cause excessive DSP logins.

Both the Admin SDK functions bypass Firestore security rules entirely (see `firestore.rules`) — they are the only components allowed to mark an order `PAID` or release someone else's amount lock.

### REST API Endpoints (Implemented)

```
GET /.netlify/functions/check-payment-now?orderId=<id>
  - Throttled on-demand DSP check + returns current order payment status
  - Output: { ok, paymentStatus, qrisAmount, paymentLockExpiresAt, checkedDsp }

(scheduled, not HTTP-invokable) netlify/functions/scheduled-check-dsp.js
  - Runs every 1 minute via Netlify Scheduled Functions
```

## Testing QRIS Implementation

### 1. Unit Test (JavaScript)

```javascript
// Test QRIS generation
const qrisString = generateQrisWithRefno(10000, "TEST001");
console.assert(qrisString.length > 0, "QRIS string generated");
console.assert(qrisString.includes("5410"), "Amount tag present");
```

### 2. Integration Test

1. Open web app in browser
2. Add items to cart
3. Proceed to checkout
4. Select QRIS payment method
5. Complete order
6. Verify QR code displays
7. Verify polling starts (check browser console)

### 3. Manual Testing

**Scenario 1: Complete Payment**
1. Scan QR code with e-wallet
2. Complete payment in e-wallet
3. Verify success message appears within 10 minutes

**Scenario 2: Timeout**
1. Scan QR code but don't complete payment
2. Wait 10 minutes
3. Verify timeout message appears

**Scenario 3: Different Payment Methods**
1. Test CASHIER payment - no QR code
2. Test BANK_TRANSFER payment - no QR code
3. Verify only QRIS shows QR code

## Security Considerations

1. **CRC Validation**: Always validate CRC before sending to e-wallet provider
2. **Reference Number**: Limits to alphanumeric + special chars (32-126 ASCII)
3. **Amount Validation**: Ensure amount matches order total
4. **Polling Timeout**: Prevents infinite polling for abandoned payments
5. **Order Locking**: Consider locking order during payment to prevent modifications

## Configuration

### Web App Settings

In `app.js`:
```javascript
const QRIS_POLLING_INTERVAL = 3000;  // 3 seconds
const QRIS_MAX_POLLING_TIME = 600000;  // 10 minutes
```

### Firestore Rules

The full, deployable ruleset lives in `firestore.rules` at the repo root (with matching composite indexes in `firestore.indexes.json`). Deploy with:

```
firebase deploy --only firestore:rules,firestore:indexes
```

Key invariants enforced:
- Anonymous customers (the web app) may only **create** orders, always starting as `paymentStatus: PENDING`, `status: PENDING` — they can never mark their own order `PAID` or update it afterwards.
- Anonymous customers may claim (`create`/`update`) a `qrisAmountLocks/{amount}` doc only if it's currently unlocked or expired, and can never set it to `RELEASED` themselves.
- Only the authenticated seller/admin app (`request.auth != null`) may update/delete orders.
- `qrisProcessedTransactions` and `system/*` are fully denied to all clients — only the Netlify Functions' Admin SDK (which bypasses rules) may read/write them.

### Environment Variables (Netlify Site Settings)

```
DS_APP_BASE_URL=https://remoteqr.dspratama.co.id
DS_APP_USERNAME=<dsp dashboard username>
DS_APP_PASSWORD=<dsp dashboard password>
DS_APP_MID=<merchant id to monitor>
FIREBASE_SERVICE_ACCOUNT_JSON=<full service account JSON, single line>
```

`FIREBASE_SERVICE_ACCOUNT_JSON` comes from Firebase Console → Project Settings → Service Accounts → Generate new private key. These are used only by `netlify/functions/*` (server-side) and are never exposed to the browser.

## Troubleshooting

### QR Code Not Displaying

1. Check if QRious library is loaded: `window.QRious`
2. Verify QRIS string is valid (check console for errors)
3. Ensure qris-util.js is loaded before app.js

### Polling Not Starting

1. Check browser console for JavaScript errors
2. Verify Firestore connection is established
3. Check if order document exists in Firestore

### Payment Not Being Detected

1. Verify order document has `paymentStatus` field in Firestore
2. Check Firestore security rules allow reading order status
3. Increase polling interval for debugging: `const pollingInterval = 1000;`

## Future Enhancements

1. **Real-time Updates**: Use Firestore listeners instead of polling
2. **Payment Gateway Integration**: Connect with actual e-wallet providers
3. **Webhook Processing**: Implement backend webhook for payment notifications
4. **Multi-Currency Support**: Extend QRIS for other currencies
5. **Transaction History**: Display payment history in seller app
6. **QR Code Customization**: Add business logo/branding to QR code
7. **Payment Analytics**: Track payment methods and success rates

## References

- BI QRIS Specification: https://www.bi.go.id/id/sistem-pembayaran/qris/
- Quick Response Code: https://en.wikipedia.org/wiki/QR_code
- CRC-16 CCITT: https://en.wikipedia.org/wiki/Cyclic_redundancy_check

## Support

For issues or questions about QRIS implementation:
1. Check browser console for error messages
2. Review Firestore rules and security
3. Test with sample QRIS strings
4. Verify QRious library is properly loaded
