// Vercel Edge Runtime Proxy → Firebase Cloud Function
// Edge поддерживает стриминг больших файлов (видео)
// и не имеет лимита на размер ответа

export const config = { runtime: 'edge' };

const FIREBASE_API = 'https://us-central1-kava-signage-2026.cloudfunctions.net/api';

export default async function handler(request) {
  // CORS preflight
  if (request.method === 'OPTIONS') {
    return new Response(null, {
      status: 200,
      headers: {
        'Access-Control-Allow-Origin': '*',
        'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
        'Access-Control-Allow-Headers': 'Content-Type',
      },
    });
  }

  const url = new URL(request.url);
  const cors = { 'Access-Control-Allow-Origin': '*' };

  try {
    // Медиа-прокси: стримим напрямую к Firebase Storage (обходя Cloud Function)
    const mediaUrl = url.searchParams.get('media');
    if (mediaUrl) {
      // Качаем напрямую из Firebase Storage — Edge-серверы не заблокированы
      const mediaResp = await fetch(mediaUrl);
      if (!mediaResp.ok) {
        return new Response(JSON.stringify({ error: `Media fetch failed: ${mediaResp.status}` }), {
          status: mediaResp.status,
          headers: { 'Content-Type': 'application/json', ...cors },
        });
      }
      return new Response(mediaResp.body, {
        status: 200,
        headers: {
          'Content-Type': mediaResp.headers.get('content-type') || 'application/octet-stream',
          'Content-Length': mediaResp.headers.get('content-length') || '',
          'Cache-Control': 'public, max-age=86400',
          ...cors,
        },
      });
    }

    // POST запросы (upload/write)
    if (request.method === 'POST') {
      const qs = url.search || '';
      const resp = await fetch(`${FIREBASE_API}${qs}`, {
        method: 'POST',
        headers: { 'Content-Type': request.headers.get('content-type') || 'application/json' },
        body: request.body,
      });
      const body = await resp.text();
      return new Response(body, {
        status: resp.status,
        headers: { 'Content-Type': 'application/json', ...cors },
      });
    }

    // GET: Firestore data proxy
    const path = url.searchParams.get('path') || 'locations';
    const resp = await fetch(`${FIREBASE_API}?path=${encodeURIComponent(path)}`);
    const body = await resp.text();
    return new Response(body, {
      status: resp.status,
      headers: {
        'Content-Type': 'application/json',
        'Cache-Control': 'public, max-age=10',
        ...cors,
      },
    });

  } catch (e) {
    return new Response(JSON.stringify({ error: e.message }), {
      status: 502,
      headers: { 'Content-Type': 'application/json', ...cors },
    });
  }
}
