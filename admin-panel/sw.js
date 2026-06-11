// Teliki — Service Worker
// ⚠️  При каждом деплое обновляйте версию CACHE_VERSION чтобы сбросить кэш у пользователей
const CACHE_VERSION = 'v13';
const CACHE_NAME = `teliki-shell-${CACHE_VERSION}`;
const SHELL_ASSETS = [
  './',
  './index.html',
  './location.html',
  './css/styles.css',
  './js/auth.js',
  './js/firebase-config.js',
  './js/dashboard.js',
  './js/location.js',
  './manifest.json'
];

// Install — cache app shell
self.addEventListener('install', (event) => {
  console.log('[Teliki SW] Installing…');
  event.waitUntil(
    caches.open(CACHE_NAME).then((cache) => {
      console.log('[Teliki SW] Caching app shell');
      return cache.addAll(SHELL_ASSETS);
    })
  );
  self.skipWaiting();
});

// Activate — clean old caches
self.addEventListener('activate', (event) => {
  console.log('[Teliki SW] Activating…');
  event.waitUntil(
    caches.keys().then((keys) =>
      Promise.all(
        keys
          .filter((key) => key !== CACHE_NAME)
          .map((key) => {
            console.log('[Teliki SW] Removing old cache:', key);
            return caches.delete(key);
          })
      )
    )
  );
  self.clients.claim();
});

// Fetch — network first, fallback to cache
self.addEventListener('fetch', (event) => {
  // Skip non-GET and cross-origin requests
  if (event.request.method !== 'GET') return;

  event.respondWith(
    fetch(event.request)
      .then((response) => {
        // Cache successful responses for shell assets only (same origin)
        if (response.status === 200) {
          const url = new URL(event.request.url);
          if (url.origin === self.location.origin) {
            const responseClone = response.clone();
            caches.open(CACHE_NAME).then((cache) => {
              cache.put(event.request, responseClone);
            });
          }
        }
        return response;
      })
      .catch(() => {
        return caches.match(event.request);
      })
  );
});
