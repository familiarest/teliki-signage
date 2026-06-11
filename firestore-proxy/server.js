const express = require('express');
const https = require('https');

const app = express();
const PORT = process.env.PORT || 3000;
const PROJECT = 'kava-signage-2026';
const BASE = `/v1/projects/${PROJECT}/databases/(default)/documents`;

// CORS — allow requests from anywhere
app.use((req, res, next) => {
  res.header('Access-Control-Allow-Origin', '*');
  res.header('Access-Control-Allow-Headers', 'Content-Type');
  next();
});

// Proxy: GET /api/locations → Firestore REST API
app.get('/api/:collection', (req, res) => {
  const path = `${BASE}/${req.params.collection}`;
  proxy(path, res);
});

// Proxy: GET /api/locations/:docId/screens → Firestore subcollection
app.get('/api/:collection/:docId/:subcollection', (req, res) => {
  const path = `${BASE}/${req.params.collection}/${req.params.docId}/${req.params.subcollection}`;
  proxy(path, res);
});

// Health check
app.get('/', (req, res) => {
  res.json({ status: 'ok', project: PROJECT });
});

function proxy(path, res) {
  const options = {
    hostname: 'firestore.googleapis.com',
    path: path,
    method: 'GET',
    headers: { 'Accept': 'application/json' }
  };

  console.log(`Proxy: ${path}`);

  const proxyReq = https.request(options, (proxyRes) => {
    let body = '';
    proxyRes.on('data', chunk => body += chunk);
    proxyRes.on('end', () => {
      res.status(proxyRes.statusCode).set('Content-Type', 'application/json').send(body);
    });
  });

  proxyReq.on('error', (e) => {
    console.error('Proxy error:', e.message);
    res.status(502).json({ error: e.message });
  });

  proxyReq.end();
}

app.listen(PORT, () => {
  console.log(`Firestore proxy running on port ${PORT}`);
});
