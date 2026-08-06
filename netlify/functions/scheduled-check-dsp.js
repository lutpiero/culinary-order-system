/**
 * Netlify Scheduled Function — runs every minute (Netlify's minimum cron
 * granularity) to reconcile recent DSP QRIS transactions against pending
 * orders, and to free expired amount locks.
 *
 * Configure required env vars in Netlify Site settings:
 *   DS_APP_BASE_URL, DS_APP_USERNAME, DS_APP_PASSWORD, DS_APP_MID
 *   FIREBASE_SERVICE_ACCOUNT_JSON
 */

const { fetchTransactions } = require("./lib/dsp-client");
const { reconcileTransactions, sweepExpiredLocks } = require("./lib/reconcile");

// Look back further than the 1-minute cron interval to tolerate DSP/network
// delays and missed runs.
const LOOKBACK_MINUTES = 15;

exports.config = {
  schedule: "* * * * *",
};

exports.handler = async () => {
  try {
    const toDate = new Date();
    const fromDate = new Date(toDate.getTime() - LOOKBACK_MINUTES * 60 * 1000);

    const transactions = await fetchTransactions({ fromDate, toDate });
    const result = await reconcileTransactions(transactions);
    const releasedLocks = await sweepExpiredLocks();

    console.log(
      `[scheduled-check-dsp] transactions=${transactions.length} matched=${result.matched} unmatched=${result.unmatched} skipped=${result.skipped} releasedLocks=${releasedLocks}`
    );

    return {
      statusCode: 200,
      body: JSON.stringify({ ok: true, ...result, releasedLocks }),
    };
  } catch (err) {
    console.error("[scheduled-check-dsp] error:", err);
    return {
      statusCode: 500,
      body: JSON.stringify({ ok: false, error: err.message }),
    };
  }
};
