// ============================================================
// Teliki — Dashboard Controller
// Reads via Cloud Function proxy (bypasses geo-blocking)
// ============================================================

(function () {
  'use strict';

  const db = window.firebaseDb;

  // Cloud Function proxy — NOT blocked, unlike firestore.googleapis.com
  const API_BASE = 'https://us-central1-kava-signage-2026.cloudfunctions.net/api';

  // ── DOM References ──────────────────────────────────────────
  const locationsGrid = document.getElementById('locationsGrid');
  const skeletonGrid  = document.getElementById('skeletonGrid');
  const emptyState    = document.getElementById('emptyState');
  const btnAddLocation = document.getElementById('btnAddLocation');
  const btnSeed       = document.getElementById('btnSeed');
  const addModal      = document.getElementById('addModal');
  const btnCancelModal = document.getElementById('btnCancelModal');
  const btnConfirmAdd  = document.getElementById('btnConfirmAdd');
  const locationNameInput = document.getElementById('locationNameInput');
  const toastContainer = document.getElementById('toastContainer');

  // ── Toast System ────────────────────────────────────────────
  function showToast(message, type = 'success') {
    const icons = { success: '✓', error: '✕', warning: '⚠' };
    const toast = document.createElement('div');
    toast.className = `toast toast--${type}`;
    toast.innerHTML = `
      <span class="toast__icon">${icons[type] || '●'}</span>
      <span class="toast__text">${message}</span>
    `;
    toastContainer.appendChild(toast);
    setTimeout(() => {
      toast.classList.add('removing');
      setTimeout(() => toast.remove(), 250);
    }, 3000);
  }

  // ── REST helpers (via proxy) ──────────────────────────────
  function parseFirestoreValue(v) {
    if ('stringValue' in v) return v.stringValue;
    if ('integerValue' in v) return parseInt(v.integerValue);
    if ('doubleValue' in v) return v.doubleValue;
    if ('booleanValue' in v) return v.booleanValue;
    if ('timestampValue' in v) return v.timestampValue;
    if ('nullValue' in v) return null;
    if ('arrayValue' in v) {
      return (v.arrayValue.values || []).map(parseFirestoreValue);
    }
    if ('mapValue' in v) {
      const obj = {};
      for (const [k, fv] of Object.entries(v.mapValue.fields || {})) {
        obj[k] = parseFirestoreValue(fv);
      }
      return obj;
    }
    return null;
  }

  function parseDoc(doc) {
    const data = {};
    for (const [k, v] of Object.entries(doc.fields || {})) {
      data[k] = parseFirestoreValue(v);
    }
    data.__id__ = doc.name.split('/').pop();
    return data;
  }

  async function proxyFetch(path) {
    const resp = await fetch(`${API_BASE}?path=${encodeURIComponent(path)}`);
    if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
    const json = await resp.json();
    return (json.documents || []).map(parseDoc);
  }

  async function proxyFetchDoc(path) {
    const resp = await fetch(`${API_BASE}?path=${encodeURIComponent(path)}`);
    if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
    const doc = await resp.json();
    return parseDoc(doc);
  }

  // ── Modal Controls ──────────────────────────────────────────
  function openModal() {
    addModal.classList.add('visible');
    locationNameInput.value = '';
    setTimeout(() => locationNameInput.focus(), 100);
  }

  function closeModal() {
    addModal.classList.remove('visible');
  }

  btnAddLocation.addEventListener('click', openModal);
  btnCancelModal.addEventListener('click', closeModal);

  addModal.addEventListener('click', (e) => {
    if (e.target === addModal) closeModal();
  });

  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape') closeModal();
  });

  // ── Create Location ────────────────────────────────────────
  async function createLocation(name) {
    try {
      const docRef = await db.collection('locations').add({
        name: name.trim(),
        created_at: firebase.firestore.FieldValue.serverTimestamp()
      });

      const batch = db.batch();
      for (let i = 1; i <= 5; i++) {
        const screenRef = db.collection('locations').doc(docRef.id)
          .collection('screens').doc(`slot_${i}`);
        batch.set(screenRef, {
          slot_number: i,
          schedule: [],
          updated_at: firebase.firestore.FieldValue.serverTimestamp()
        });
      }
      await batch.commit();

      showToast(`Кофейня «${name}» добавлена`);
      closeModal();
      loadLocations();
    } catch (err) {
      console.error('[Teliki] Error creating location:', err);
      showToast('Ошибка при создании кофейни', 'error');
    }
  }

  btnConfirmAdd.addEventListener('click', () => {
    const name = locationNameInput.value.trim();
    if (!name) {
      locationNameInput.style.borderColor = 'var(--danger)';
      setTimeout(() => locationNameInput.style.borderColor = '', 1500);
      return;
    }
    createLocation(name);
  });

  locationNameInput.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') btnConfirmAdd.click();
  });

  // ── Seed Default Locations ─────────────────────────────────
  const DEFAULT_LOCATIONS = [
    'Ак-Мечеть', 'Франко', 'Пушкина',
    'Евпатория', 'Саки', 'Бахчисарай'
  ];

  btnSeed.addEventListener('click', async () => {
    if (!confirm('Создать 6 стандартных кофеен?\n\nАк-Мечеть, Франко, Пушкина, Евпатория, Саки, Бахчисарай')) return;

    btnSeed.disabled = true;
    btnSeed.textContent = 'Создание…';

    try {
      // Check existing via proxy
      const existing = await proxyFetch('locations');
      const existingNames = new Set(existing.map(d => d.name));

      for (const name of DEFAULT_LOCATIONS) {
        if (existingNames.has(name)) continue;

        const docRef = await db.collection('locations').add({
          name,
          created_at: firebase.firestore.FieldValue.serverTimestamp()
        });

        const batch = db.batch();
        for (let i = 1; i <= 5; i++) {
          const screenRef = db.collection('locations').doc(docRef.id)
            .collection('screens').doc(`slot_${i}`);
          batch.set(screenRef, {
            slot_number: i,
            schedule: [],
            updated_at: firebase.firestore.FieldValue.serverTimestamp()
          });
        }
        await batch.commit();
      }
      showToast('База инициализирована — 6 кофеен');
      loadLocations();
    } catch (err) {
      console.error('[Teliki] Seed error:', err);
      showToast('Ошибка при инициализации базы', 'error');
    } finally {
      btnSeed.disabled = false;
      btnSeed.innerHTML = '<span>⚡</span> Инициализировать базу';
    }
  });

  // ── Location Icons ─────────────────────────────────────────
  const LOCATION_ICONS = ['☕', '🏠', '🏪', '🏬', '🏢', '🏗'];

  function getLocationIcon(index) {
    return LOCATION_ICONS[index % LOCATION_ICONS.length];
  }

  // ── Render Location Card ───────────────────────────────────
  function createLocationCard(doc, index) {
    const card = document.createElement('div');
    card.className = 'card';
    card.setAttribute('role', 'button');
    card.setAttribute('tabindex', '0');

    card.innerHTML = `
      <span class="card-icon">${getLocationIcon(index)}</span>
      <div class="card-name"></div>
      <span class="card-badge">Открыть →</span>
    `;
    card.querySelector('.card-name').textContent = doc.name;

    const navigate = () => {
      window.location.href = `location.html?id=${doc.__id__}`;
    };
    card.addEventListener('click', navigate);
    card.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' || e.key === ' ') {
        e.preventDefault();
        navigate();
      }
    });

    return card;
  }

  // ── Load & Render ─────────────────────────────────────────
  async function loadLocations() {
    try {
      const docs = await proxyFetch('locations');

      skeletonGrid.classList.add('hidden');

      if (docs.length === 0) {
        locationsGrid.classList.add('hidden');
        emptyState.classList.remove('hidden');
        return;
      }

      emptyState.classList.add('hidden');
      locationsGrid.classList.remove('hidden');

      docs.sort((a, b) => (a.name || '').localeCompare(b.name || ''));

      locationsGrid.innerHTML = '';
      docs.forEach((doc, idx) => {
        locationsGrid.appendChild(createLocationCard(doc, idx));
      });
    } catch (err) {
      console.error('[Teliki] Load error:', err);
      skeletonGrid.classList.add('hidden');
      showToast('Ошибка загрузки: ' + err.message, 'error');
    }
  }

  // ── Init ───────────────────────────────────────────────────
  loadLocations();
  setInterval(loadLocations, 30000);

})();
