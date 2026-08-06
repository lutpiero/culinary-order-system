/**
 * Firebase Configuration
 * 
 * This file is auto-generated during build/deployment.
 * Environment variables are injected by Netlify or GitHub Actions.
 * 
 * For local development, replace the placeholder values with your
 * actual Firebase configuration from Firebase Console.
 */

window.FIREBASE_CONFIG = {
  apiKey: "${FIREBASE_API_KEY}",
  authDomain: "${FIREBASE_AUTH_DOMAIN}",
  projectId: "${FIREBASE_PROJECT_ID}",
  storageBucket: "${FIREBASE_STORAGE_BUCKET}",
  messagingSenderId: "${FIREBASE_MESSAGING_SENDER_ID}",
  appId: "${FIREBASE_APP_ID}"
};

/**
 * QRIS Configuration
 * 
 * Merchant-specific QRIS data for payment processing.
 * These values should be provided by your acquiring bank.
 */
window.QRIS_CONFIG = {
  // QRIS prefix and merchant data from your acquiring bank
  // This template comes from a test acquirer and should be replaced
  // with your actual merchant information from your bank
  prefix: "00020101021226710019ID.CO.DSPRATAMA.WWW011893600998000001350302159980260752866590303UMI51440014ID.CO.QRIS.WWW0215ID10265469836970303UMI520486615303360",
  middle: "5802ID5918MASJID NURUL ISLAM6015JAKARTA SELATAN610512720",
  suffix: "6304",
  
  // Merchant details (customize these for your business)
  merchantName: "MASJID NURUL ISLAM",
  merchantCity: "JAKARTA SELATAN",
  merchantPostalCode: "12720",
  
  // Polling configuration
  pollingInterval: 3000,     // Poll every 3 seconds
  maxPollingTime: 600000,    // Stop polling after 10 minutes

  // Amount-locking configuration (see QRIS_IMPLEMENTATION_GUIDE.md)
  // Each QRIS order reserves its exact amount for `lockDurationMs`. If the
  // same base amount is requested again while still locked, `feeAmount` is
  // added repeatedly until a free amount is found, so every concurrently
  // pending QRIS order has a unique, unambiguous amount.
  feeAmount: 1,              // Rupiah added per collision (qris_fee)
  lockDurationMs: 300000,    // 5 minutes
  maxReservationAttempts: 50,

  // On-demand backend endpoint that nudges a faster DSP transaction check
  // (in addition to the once-per-minute scheduled function) while a QRIS
  // payment is actively being polled.
  checkPaymentEndpoint: "/.netlify/functions/check-payment-now"
};

/**
 * Application Settings
 */
window.APP_CONFIG = {
  // Add other app-level configuration here as needed
};