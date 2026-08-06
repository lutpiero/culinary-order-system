/**
 * Core reconciliation logic: matches DSP QRIS transactions to pending orders
 * by exact amount, and manages the qrisAmountLocks lifecycle.
 *
 * Collections used:
 *   - orders                    (existing) fields used: paymentMethod,
 *                                paymentStatus, qrisAmount, createdAt
 *   - qrisAmountLocks/{amount}   { amount, orderId, tableNumber, lockedAt,
 *                                  expiresAt, status: LOCKED|RELEASED }
 *   - qrisProcessedTransactions/{transactionId}
 *                                dedupe record for processed DSP transactions
 */

const { admin, getDb } = require("./firebase-admin");

const APPROVED_STATUSES = new Set(["APPROVED", "SUCCESS", "SUCCESSFUL"]);

function transactionId(tx) {
  return tx.TRANSACTION_ID || tx.RRN || null;
}

/**
 * Reconcile a batch of DSP transactions against pending Firestore orders.
 * @param {Array<Object>} transactions - raw transaction records from DSP
 * @returns {Promise<{matched: number, unmatched: number, skipped: number}>}
 */
async function reconcileTransactions(transactions) {
  const db = getDb();
  let matched = 0;
  let unmatched = 0;
  let skipped = 0;

  for (const tx of transactions) {
    const status = String(tx.TRANSACTION_STATUS || "").toUpperCase();
    if (!APPROVED_STATUSES.has(status)) continue;

    const txId = transactionId(tx);
    if (!txId) {
      console.warn("Skipping DSP transaction with no TRANSACTION_ID/RRN:", tx);
      continue;
    }

    const amount = Number(tx.AMOUNT);
    if (!Number.isFinite(amount)) {
      console.warn("Skipping DSP transaction with invalid AMOUNT:", tx);
      continue;
    }

    const processedRef = db.collection("qrisProcessedTransactions").doc(String(txId));

    const result = await db.runTransaction(async (t) => {
      const processedSnap = await t.get(processedRef);
      if (processedSnap.exists) {
        return "SKIPPED";
      }

      // Find the oldest pending QRIS order that matches this exact amount.
      const ordersQuery = db
        .collection("orders")
        .where("paymentMethod", "==", "QRIS")
        .where("paymentStatus", "==", "PENDING")
        .where("qrisAmount", "==", amount)
        .orderBy("createdAt", "asc")
        .limit(1);

      const ordersSnap = await t.get(ordersQuery);

      const now = admin.firestore.Timestamp.now();

      if (ordersSnap.empty) {
        t.set(processedRef, {
          amount,
          transactionId: txId,
          rrn: tx.RRN || null,
          status: "UNMATCHED",
          matchedOrderId: null,
          processedAt: now,
        });
        return "UNMATCHED";
      }

      const orderDoc = ordersSnap.docs[0];

      // Release the amount lock so it can be reused, but only if it still
      // points at this order (avoid releasing a newer order's lock).
      // Firestore requires all reads to precede all writes in a transaction,
      // so this lock read must happen before the order update below.
      const lockRef = db.collection("qrisAmountLocks").doc(String(amount));
      const lockSnap = await t.get(lockRef);

      t.update(orderDoc.ref, {
        paymentStatus: "PAID",
        matchedTransactionId: txId,
        matchedRrn: tx.RRN || null,
        paidAmount: amount,
        paidAt: now,
        updatedAt: now,
      });

      if (lockSnap.exists && lockSnap.data().orderId === orderDoc.id) {
        t.update(lockRef, { status: "RELEASED", releasedAt: now, releasedReason: "PAID" });
      }

      t.set(processedRef, {
        amount,
        transactionId: txId,
        rrn: tx.RRN || null,
        status: "MATCHED",
        matchedOrderId: orderDoc.id,
        processedAt: now,
      });

      return "MATCHED";
    });

    if (result === "MATCHED") matched++;
    else if (result === "UNMATCHED") unmatched++;
    else skipped++;
  }

  return { matched, unmatched, skipped };
}

/**
 * Free any amount locks whose 5-minute hold has expired. The corresponding
 * order is intentionally left untouched (stays PENDING) so a late payment
 * can still be matched by amount as long as it hasn't been reused by a
 * newer order in the meantime.
 */
async function sweepExpiredLocks() {
  const db = getDb();
  const now = admin.firestore.Timestamp.now();

  const snap = await db
    .collection("qrisAmountLocks")
    .where("status", "==", "LOCKED")
    .where("expiresAt", "<", now)
    .get();

  if (snap.empty) return 0;

  const batch = db.batch();
  snap.docs.forEach((docSnap) => {
    batch.update(docSnap.ref, {
      status: "RELEASED",
      releasedAt: now,
      releasedReason: "EXPIRED",
    });
  });
  await batch.commit();
  return snap.size;
}

module.exports = {
  reconcileTransactions,
  sweepExpiredLocks,
};
