/**
 * Signed deletion of a Cloudinary image.
 *
 * The seller Android app stores Cloudinary secure_urls in Firestore. Deletion
 * requires the Cloudinary Admin API (which is signed with the API secret), so
 * it cannot be done from the client — this function does it instead.
 *
 * Query params:
 *   url (required) — the Cloudinary secure_url of the image to delete
 *
 * Headers:
 *   Authorization: Bearer <Firebase ID token> — must be a valid, signed-in
 *   seller/admin user (matches how uploads are authorized in the app).
 *
 * Env vars required (Netlify):
 *   FIREBASE_SERVICE_ACCOUNT_JSON  (already used by the other functions)
 *   CLOUDINARY_CLOUD_NAME
 *   CLOUDINARY_API_KEY
 *   CLOUDINARY_API_SECRET
 */

const crypto = require("crypto");
const https = require("https");
const { admin } = require("./lib/firebase-admin");

function publicIdFromUrl(url) {
  try {
    const parsed = new URL(url);
    const marker = "/image/upload/";
    const idx = parsed.pathname.indexOf(marker);
    if (idx === -1) return null;
    let rest = parsed.pathname.slice(idx + marker.length);
    rest = rest.replace(/^v\d+\//, "");
    if (!rest) return null;
    const lastSlash = rest.lastIndexOf("/");
    const fileName = lastSlash === -1 ? rest : rest.slice(lastSlash + 1);
    const dot = fileName.lastIndexOf(".");
    if (dot > 0) {
      rest = rest.slice(0, lastSlash + 1) + fileName.slice(0, dot);
    }
    return rest;
  } catch (err) {
    return null;
  }
}

function cloudinaryDestroy({ cloudName, apiKey, apiSecret, publicId }) {
  return new Promise((resolve, reject) => {
    const timestamp = String(Math.round(Date.now() / 1000));
    const params = { public_id: publicId, timestamp };

    const toSign = Object.keys(params)
      .sort()
      .map((k) => `${k}=${params[k]}`)
      .join("&");
    const signature = crypto.createHash("sha1").update(toSign + apiSecret).digest("hex");

    const body = new URLSearchParams({
      public_id: publicId,
      timestamp,
      api_key: apiKey,
      signature,
      invalidate: "true",
    }).toString();

    const req = https.request(
      {
        hostname: "api.cloudinary.com",
        path: `/v1_1/${cloudName}/image/destroy`,
        method: "POST",
        headers: {
          "Content-Type": "application/x-www-form-urlencoded",
          "Content-Length": Buffer.byteLength(body),
        },
      },
      (res) => {
        let data = "";
        res.on("data", (chunk) => (data += chunk));
        res.on("end", () => {
          let json = null;
          try {
            json = JSON.parse(data);
          } catch (err) {
            json = { error: { message: data || "Invalid response" } };
          }
          if (res.statusCode >= 400) {
            reject(new Error(`Cloudinary destroy failed (${res.statusCode}): ${(json && json.error && json.error.message) || data}`));
            return;
          }
          if (json && json.result === "ok") {
            resolve();
          } else {
            reject(new Error(`Cloudinary destroy returned: ${(json && json.result) || "unknown"}`));
          }
        });
      }
    );
    req.on("error", reject);
    req.write(body);
    req.end();
  });
}

exports.handler = async (event) => {
  const authHeader = event.headers.authorization || event.headers.Authorization || "";
  const token = authHeader.startsWith("Bearer ") ? authHeader.slice(7) : null;
  if (!token) {
    return { statusCode: 401, body: JSON.stringify({ ok: false, error: "Missing bearer token" }) };
  }

  try {
    await admin.auth().verifyIdToken(token);
  } catch (err) {
    return { statusCode: 401, body: JSON.stringify({ ok: false, error: "Invalid token" }) };
  }

  const url = (event.queryStringParameters || {}).url;
  if (!url) {
    return { statusCode: 400, body: JSON.stringify({ ok: false, error: "Missing url" }) };
  }

  const publicId = publicIdFromUrl(url);
  if (!publicId) {
    return { statusCode: 400, body: JSON.stringify({ ok: false, error: "Invalid Cloudinary URL" }) };
  }

  const cloudName = process.env.CLOUDINARY_CLOUD_NAME;
  const apiKey = process.env.CLOUDINARY_API_KEY;
  const apiSecret = process.env.CLOUDINARY_API_SECRET;
  if (!cloudName || !apiKey || !apiSecret) {
    return {
      statusCode: 500,
      body: JSON.stringify({ ok: false, error: "Cloudinary credentials not configured" }),
    };
  }

  try {
    await cloudinaryDestroy({ cloudName, apiKey, apiSecret, publicId });
    return { statusCode: 200, body: JSON.stringify({ ok: true, publicId }) };
  } catch (err) {
    console.error("[delete-image] error:", err.message);
    return { statusCode: 500, body: JSON.stringify({ ok: false, error: err.message }) };
  }
};
