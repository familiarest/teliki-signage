// Vercel Serverless Proxy → Firebase Cloud Function
// Обходит гео-блокировку Google доменов
const https = require('https');
const http = require('http');

const FIREBASE_API = 'https://us-central1-kava-signage-2026.cloudfunctions.net/api';

module.exports = async (req, res) => {
  // CORS
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type');
  if (req.method === 'OPTIONS') { res.status(200).end(); return; }

  // Собираем query string
  const params = new URLSearchParams(req.query);
  // Убираем vercel-специфичные параметры
  params.delete('');
  const qs = params.toString();
  const targetUrl = qs ? `${FIREBASE_API}?${qs}` : FIREBASE_API;

  try {
    if (req.method === 'GET') {
      // GET запрос — проксируем
      const data = await fetchUrl(targetUrl);
      
      // Определяем Content-Type по содержимому
      if (req.query.media) {
        // Медиа — передаём бинарные данные
        const mediaData = await fetchBinary(targetUrl);
        res.setHeader('Content-Type', mediaData.contentType || 'application/octet-stream');
        res.setHeader('Cache-Control', 'public, max-age=86400');
        res.status(mediaData.status).send(mediaData.body);
      } else {
        res.setHeader('Content-Type', 'application/json');
        res.setHeader('Cache-Control', 'public, max-age=10');
        res.status(200).send(data);
      }
    } else if (req.method === 'POST') {
      // POST запрос (upload/write)
      const postData = await postToUrl(targetUrl, req.body, req.headers['content-type']);
      res.setHeader('Content-Type', 'application/json');
      res.status(200).send(postData);
    } else {
      res.status(405).json({ error: 'Method not allowed' });
    }
  } catch (e) {
    console.error('Proxy error:', e.message);
    res.status(502).json({ error: e.message });
  }
};

function fetchUrl(url) {
  return new Promise((resolve, reject) => {
    https.get(url, { headers: { 'Accept': 'application/json' } }, (resp) => {
      let data = '';
      resp.on('data', (chunk) => data += chunk);
      resp.on('end', () => resolve(data));
    }).on('error', reject);
  });
}

function fetchBinary(url) {
  return new Promise((resolve, reject) => {
    https.get(url, (resp) => {
      const chunks = [];
      resp.on('data', (chunk) => chunks.push(chunk));
      resp.on('end', () => {
        resolve({
          status: resp.statusCode,
          contentType: resp.headers['content-type'],
          body: Buffer.concat(chunks)
        });
      });
    }).on('error', reject);
  });
}

function postToUrl(url, body, contentType) {
  return new Promise((resolve, reject) => {
    const parsed = new URL(url);
    const postBody = typeof body === 'string' ? body : JSON.stringify(body);
    const options = {
      hostname: parsed.hostname,
      path: parsed.pathname + parsed.search,
      method: 'POST',
      headers: {
        'Content-Type': contentType || 'application/json',
        'Content-Length': Buffer.byteLength(postBody)
      }
    };
    const req = https.request(options, (resp) => {
      let data = '';
      resp.on('data', (chunk) => data += chunk);
      resp.on('end', () => resolve(data));
    });
    req.on('error', reject);
    req.write(postBody);
    req.end();
  });
}
