// Vercel Serverless Proxy → Firebase Cloud Function
// Обходит гео-блокировку Google доменов
// Поддерживает стриминг больших файлов (видео)
const https = require('https');

const FIREBASE_API = 'https://us-central1-kava-signage-2026.cloudfunctions.net/api';

// Снимаем лимит на размер ответа
module.exports.config = {
  api: {
    responseLimit: false,
  },
};

module.exports = (req, res) => {
  // CORS
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type');
  if (req.method === 'OPTIONS') { res.status(200).end(); return; }

  // Собираем query string
  const params = new URLSearchParams(req.query);
  const qs = params.toString();
  const targetUrl = qs ? `${FIREBASE_API}?${qs}` : FIREBASE_API;

  if (req.method === 'GET') {
    // Стримим ответ напрямую — без буферизации в памяти
    https.get(targetUrl, (proxyRes) => {
      res.status(proxyRes.statusCode);

      // Пробрасываем заголовки
      const ct = proxyRes.headers['content-type'];
      if (ct) res.setHeader('Content-Type', ct);
      const cl = proxyRes.headers['content-length'];
      if (cl) res.setHeader('Content-Length', cl);

      // Кэш: медиа на сутки, данные на 10 сек
      if (req.query.media) {
        res.setHeader('Cache-Control', 'public, max-age=86400');
      } else {
        res.setHeader('Cache-Control', 'public, max-age=10');
      }

      // Стрим без буферизации
      proxyRes.pipe(res);
    }).on('error', (e) => {
      console.error('Proxy GET error:', e.message);
      res.status(502).json({ error: e.message });
    });

  } else if (req.method === 'POST') {
    const postBody = typeof req.body === 'string' ? req.body : JSON.stringify(req.body);
    const parsed = new URL(targetUrl);
    const options = {
      hostname: parsed.hostname,
      path: parsed.pathname + parsed.search,
      method: 'POST',
      headers: {
        'Content-Type': req.headers['content-type'] || 'application/json',
        'Content-Length': Buffer.byteLength(postBody),
      },
    };

    const proxyReq = https.request(options, (proxyRes) => {
      res.status(proxyRes.statusCode);
      const ct = proxyRes.headers['content-type'];
      if (ct) res.setHeader('Content-Type', ct);
      proxyRes.pipe(res);
    });

    proxyReq.on('error', (e) => {
      console.error('Proxy POST error:', e.message);
      res.status(502).json({ error: e.message });
    });

    proxyReq.write(postBody);
    proxyReq.end();
  } else {
    res.status(405).json({ error: 'Method not allowed' });
  }
};
