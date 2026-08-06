/**
 * On-demand HTTP function invoked by the client's payment-status polling
 * loop. It nudges a DSP transaction check faster than the once-per-minute
 * scheduled function, but throttles actual DSP calls globally (across all
 * concurrently-polling customers) via a Firestore doc so we don't hammer
 * the DSP dashboard with logins.
 *
 * Query params:
 *   orderId (required) — the order whose current status should be returned
 *
 * Response body:
 *   { paymentStatus, qrisAmount, paymentLockExpiresAt, checkedDsp }
 */

const { fetchTransactions } = require("./lib/dsp-client");
const { reconcileTransactions, sweepExpiredLocks } = require("./lib/reconcile");
const { admin, getDb } = require("./lib/firebase-admin");

// Minimum time between real DSP checks, shared globally across all clients.
const THROTTLE_MS = 7000;
// Narrow lookback window for on-demand checks (fast path); the scheduled
// function still covers a wider window as a safety net.
const LOOKBACK_MINUTES = 6;

const THROTTLE_DOC_PATH = ["system", "dspPollThrottle"];

async function tryClaimThrottleSlot(db) {
  const ref = db.collection(THROTTLE_DOC_PATH[0]).doc(THROTTLE_DOC_PATH[1]);
  const now = Date.now();

  return db.runTransaction(async (t) => {
    const snap = await t.get(ref);
    const lastPolledAtMs = snap.exists && snap.data().lastPolledAtMs ? snap.data().lastPolledAtMs : 0;

    if (now - lastPolledAtMs < THROTTLE_MS) {
      return false;
    }

    t.set(ref, { lastPolledAtMs: now, updatedAt: admin.firestore.Timestamp.now() }, { merge: true });
    return true;
  });
}

exports.handler = async (event) => {
  const orderId = event.queryStringParameters && event.queryStringParameters.orderId;
  if (!orderId) {
    return {
      statusCode: 400,
      body: JSON.stringify({ ok: false, error: "Missing required query param: orderId" }),
    };
  }

  const db = getDb();
  let checkedDsp = false;

  try {
    const claimed = await tryClaimThrottleSlot(db);

    if (claimed) {
      checkedDsp = true;
      const toDate = new Date();
      const fromDate = new Date(toDate.getTime() - LOOKBACK_MINUTES * 60 * 1000);
      const transactions = await fetchTransactions({ fromDate, toDate });
      await reconcileTransactions(transactions);
      await sweepExpiredLocks();
    }

    const orderSnap = await db.collection("orders").doc(orderId).get();
    if (!orderSnap.exists) {
      return {
        statusCode: 404,
        body: JSON.stringify({ ok: false, error: "Order not found" }),
      };
    }

    const order = orderSnap.data();
    const paymentLockExpiresAt =
      order.paymentLockExpiresAt && order.paymentLockExpiresAt.toMillis
        ? order.paymentLockExpiresAt.toMillis()
        : null;

    return {
      statusCode: 200,
      body: JSON.stringify({
        ok: true,
        paymentStatus: order.paymentStatus || null,
        qrisAmount: order.qrisAmount ?? null,
        paymentLockExpiresAt,
        checkedDsp,
      }),
    };
  } catch (err) {
    console.error("[check-payment-now] error:", err);
    return {
      statusCode: 500,
      body: JSON.stringify({ ok: false, error: err.message, checkedDsp }),
    };
  }
};
