/**
 * Firebase Admin SDK initialization for Netlify Functions.
 *
 * Requires the FIREBASE_SERVICE_ACCOUNT_JSON environment variable, containing
 * the full JSON key downloaded from:
 *   Firebase Console -> Project Settings -> Service Accounts -> Generate new private key
 *
 * This is intentionally separate from the public FIREBASE_CONFIG used by the
 * client (web/config.js) since it grants elevated (Admin SDK) access and
 * must never be exposed to the browser.
 */

const admin = require("firebase-admin");

let app = null;

function getFirebaseApp() {
  if (app) return app;

  const raw = process.env.FIREBASE_SERVICE_ACCOUNT_JSON;
  if (!raw) {
    throw new Error("Missing required environment variable: FIREBASE_SERVICE_ACCOUNT_JSON");
  }

  let serviceAccount;
  try {
    serviceAccount = JSON.parse(raw);
  } catch (err) {
    throw new Error("FIREBASE_SERVICE_ACCOUNT_JSON is not valid JSON: " + err.message);
  }

  app = admin.apps.length
    ? admin.app()
    : admin.initializeApp({
        credential: admin.credential.cert(serviceAccount),
      });

  return app;
}

function getDb() {
  return getFirebaseApp().firestore();
}

module.exports = {
  admin,
  getFirebaseApp,
  getDb,
};
