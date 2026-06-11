const {onRequest} = require("firebase-functions/v2/https");
const admin = require("firebase-admin");
const https = require("https");

admin.initializeApp();
const bucket = admin.storage().bucket();
const firestore = admin.firestore();

const PROJECT = "kava-signage-2026";
const FIRESTORE_BASE = `https://firestore.googleapis.com/v1/projects/${PROJECT}/databases/(default)/documents`;

exports.api = onRequest({cors: true, region: "us-central1", memory: "512MiB", timeoutSeconds: 120}, async (req, res) => {

  // ── Media proxy: GET /api?media=ENCODED_URL ──
  if (req.query.media) {
    const mediaUrl = req.query.media;
    https.get(mediaUrl, (proxyRes) => {
      res.status(proxyRes.statusCode);
      if (proxyRes.headers["content-type"]) res.set("Content-Type", proxyRes.headers["content-type"]);
      if (proxyRes.headers["content-length"]) res.set("Content-Length", proxyRes.headers["content-length"]);
      res.set("Cache-Control", "public, max-age=86400");
      proxyRes.pipe(res);
    }).on("error", (e) => {
      res.status(502).json({error: e.message});
    });
    return;
  }

  // ── File upload: POST /api?upload=PATH ──
  if (req.method === "POST" && req.query.upload) {
    try {
      const storagePath = req.query.upload;
      const contentType = req.headers["content-type"] || "application/octet-stream";

      const file = bucket.file(storagePath);
      const stream = file.createWriteStream({
        metadata: { contentType },
        resumable: false,
      });

      await new Promise((resolve, reject) => {
        stream.on("finish", resolve);
        stream.on("error", reject);
        // req.rawBody is available in Firebase Functions
        if (req.rawBody) {
          stream.end(req.rawBody);
        } else {
          req.pipe(stream);
        }
      });

      // Make file publicly readable and get URL
      await file.makePublic();
      const publicUrl = `https://storage.googleapis.com/${bucket.name}/${storagePath}`;

      // Also get the Firebase download URL
      const [metadata] = await file.getMetadata();
      const token = metadata.metadata && metadata.metadata.firebaseStorageDownloadTokens;
      const firebaseUrl = token
        ? `https://firebasestorage.googleapis.com/v0/b/${bucket.name}/o/${encodeURIComponent(storagePath)}?alt=media&token=${token}`
        : publicUrl;

      res.json({ url: firebaseUrl, publicUrl, path: storagePath });
    } catch (e) {
      console.error("Upload error:", e);
      res.status(500).json({ error: e.message });
    }
    return;
  }

  // ── Firestore write: POST /api?write=COLLECTION_PATH ──
  if (req.method === "POST" && req.query.write) {
    try {
      const docPath = req.query.write;
      const data = req.body;

      if (!data || typeof data !== "object") {
        return res.status(400).json({error: "Invalid JSON body"});
      }

      // Convert serverTimestamp markers
      function processTimestamps(obj) {
        for (const key in obj) {
          if (obj[key] === "__SERVER_TIMESTAMP__") {
            obj[key] = admin.firestore.FieldValue.serverTimestamp();
          } else if (obj[key] && typeof obj[key] === "object" && !Array.isArray(obj[key])) {
            processTimestamps(obj[key]);
          }
        }
      }
      processTimestamps(data);

      const ref = firestore.doc(docPath);
      await ref.set(data, {merge: true});
      res.json({success: true, path: docPath});
    } catch (e) {
      console.error("Write error:", e);
      res.status(500).json({error: e.message});
    }
    return;
  }

  // ── Firestore read: GET /api?path=COLLECTION ──
  const path = req.query.path || "locations";
  const url = `${FIRESTORE_BASE}/${path}`;

  https.get(url, {headers: {"Accept": "application/json"}}, (proxyRes) => {
    let body = "";
    proxyRes.on("data", (chunk) => body += chunk);
    proxyRes.on("end", () => {
      res.status(proxyRes.statusCode).set("Content-Type", "application/json").send(body);
    });
  }).on("error", (e) => {
    res.status(502).json({error: e.message});
  });
});
