// ============================================================
// Teliki — Location Detail & Chain Schedule Editor
// ============================================================

(function () {
  'use strict';

  const db = window.firebaseDb;
  const storage = window.firebaseStorage;

  // ── URL Params ─────────────────────────────────────────────
  const urlParams = new URLSearchParams(window.location.search);
  const locationId = urlParams.get('id');

  if (!locationId) {
    window.location.href = 'index.html';
    return;
  }

  // ── DOM References ─────────────────────────────────────────
  const locationTitle = document.getElementById('locationTitle');
  const slotsGrid = document.getElementById('slotsGrid');
  const chainEditorContainer = document.getElementById('chainEditorContainer');
  const toastContainer = document.getElementById('toastContainer');
  const btnDeploy = document.getElementById('btnDeploy');

  // ── State ──────────────────────────────────────────────────
  let currentSlot = null;       // currently selected slot number (1-5)
  let currentSchedule = [];     // schedule array being edited
  let pendingFiles = {};        // { mediaIndex: File }
  let existingSchedule = [];    // original schedule from Firestore

  // ── Toast System ───────────────────────────────────────────
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

  // Cloud Function proxy
  const API_BASE = 'https://us-central1-kava-signage-2026.cloudfunctions.net/api';

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

  function parseRestDoc(doc) {
    const data = {};
    for (const [k, v] of Object.entries(doc.fields || {})) {
      data[k] = parseFirestoreValue(v);
    }
    return data;
  }

  // Proxy media URLs through Cloud Function to bypass geo-blocking
  function proxyMediaUrl(url) {
    if (!url) return url;
    if (url.includes('firebasestorage.googleapis.com') || url.includes('storage.googleapis.com')) {
      return `${API_BASE}?media=${encodeURIComponent(url)}`;
    }
    return url;
  }

  // ── Load Location Name ────────────────────────────────────
  async function loadLocationName() {
    try {
      const resp = await fetch(`${API_BASE}?path=locations/${locationId}`);
      if (!resp.ok) {
        showToast('Локация не найдена', 'error');
        setTimeout(() => window.location.href = 'index.html', 1500);
        return false;
      }
      const doc = await resp.json();
      const data = parseRestDoc(doc);
      const name = data.name || 'Без имени';
      locationTitle.textContent = name;
      document.title = `Teliki — ${name}`;
      return true;
    } catch (err) {
      console.error('[Teliki] Error loading location:', err);
      showToast('Ошибка загрузки локации', 'error');
      return false;
    }
  }

  // ── Load Slots ─────────────────────────────────────────────
  async function loadSlots() {
    try {
      const resp = await fetch(`${API_BASE}?path=locations/${locationId}/screens`);
      if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
      const json = await resp.json();
      let screenDocs = (json.documents || []).map(d => ({
        id: d.name.split('/').pop(),
        data: parseRestDoc(d)
      }));

      slotsGrid.innerHTML = '';

      // If no screens exist, create them
      if (screenDocs.length === 0) {
        const batch = db.batch();
        for (let i = 1; i <= 5; i++) {
          const ref = db.collection('locations').doc(locationId)
            .collection('screens').doc(`slot_${i}`);
          batch.set(ref, {
            slot_number: i,
            schedule: [],
            updated_at: firebase.firestore.FieldValue.serverTimestamp()
          });
        }
        await batch.commit();
        // Wait a moment for Firestore to propagate, then reload
        await new Promise(r => setTimeout(r, 1000));
        return loadSlots();
      }

      screenDocs.sort((a, b) => (a.data.slot_number || 0) - (b.data.slot_number || 0));

      screenDocs.forEach(({ id, data }) => {
        const slotNum = data.slot_number;
        const schedule = data.schedule || [];
        const isFilled = schedule.length > 0;
        const firstMedia = isFilled ? schedule[0] : null;

        const card = document.createElement('div');
        card.className = 'slot-card' + (currentSlot === slotNum ? ' active' : '');
        card.setAttribute('role', 'button');
        card.setAttribute('tabindex', '0');

        let previewContent;
        if (firstMedia && firstMedia.media_type === 'image' && firstMedia.media_url) {
          previewContent = `<img src="${proxyMediaUrl(firstMedia.media_url)}" alt="Preview" loading="lazy">`;
        } else if (firstMedia && firstMedia.media_type === 'video') {
          previewContent = `<span style="font-size:28px;color:var(--text-secondary)">🎬</span>`;
        } else {
          previewContent = `<span class="slot-card__preview-empty">Пусто</span>`;
        }

        const statusClass = isFilled ? 'slot-card__status slot-card__status--filled' : 'slot-card__status';
        const statusText = isFilled ? `${schedule.length} медиа` : 'Не настроен';

        card.innerHTML = `
          <div class="slot-card__number">TV ${slotNum}</div>
          <div class="slot-card__preview">${previewContent}</div>
          <div class="${statusClass}">${statusText}</div>
        `;

        const handleClick = () => openSlotEditor(slotNum, schedule);
        card.addEventListener('click', handleClick);
        card.addEventListener('keydown', (e) => {
          if (e.key === 'Enter' || e.key === ' ') {
            e.preventDefault();
            handleClick();
          }
        });

        slotsGrid.appendChild(card);
      });
    } catch (err) {
      console.error('[Teliki] Error loading slots:', err);
      showToast('Ошибка загрузки экранов', 'error');
    }

    // Показываем кнопку «Выгрузить»
    btnDeploy.style.display = '';
  }

  // ── Open Slot Editor ───────────────────────────────────────
  function openSlotEditor(slotNumber, schedule) {
    currentSlot = slotNumber;
    existingSchedule = JSON.parse(JSON.stringify(schedule));
    currentSchedule = JSON.parse(JSON.stringify(schedule));
    pendingFiles = {};

    // Highlight active slot
    document.querySelectorAll('.slot-card').forEach((card, idx) => {
      card.classList.toggle('active', (idx + 1) === slotNumber);
    });

    renderChainEditor();
  }

  // ── Render Chain Editor ────────────────────────────────────
  function renderChainEditor() {
    // Ensure at least one empty media card if schedule is empty
    if (currentSchedule.length === 0) {
      currentSchedule.push({
        media_url: '',
        media_type: '',
        file_name: '',
        has_schedule: false,
        end_time: null
      });
    }

    let html = `
      <div class="chain-editor">
        <h2 class="chain-editor__title">TV ${currentSlot} — Расписание</h2>
        <div id="mediaCardsContainer">
    `;

    currentSchedule.forEach((media, index) => {
      const isLast = index === currentSchedule.length - 1;
      html += renderMediaCard(media, index, isLast);
      if (!isLast) {
        html += `
          <div class="chain-connector">
            <div class="chain-connector__line"></div>
          </div>
        `;
      }
    });

    html += `
        </div>
        <div class="chain-editor__actions">
          <button class="btn btn-secondary" onclick="window.__teliki.closeEditor()">Отмена</button>
          <button class="btn btn-primary" id="btnSaveSchedule">💾 Сохранить</button>
        </div>
      </div>
    `;

    chainEditorContainer.innerHTML = html;

    // Attach save handler
    document.getElementById('btnSaveSchedule').addEventListener('click', saveSchedule);

    // Attach all event handlers
    attachMediaCardHandlers();
  }

  // ── Render Single Media Card ───────────────────────────────
  function renderMediaCard(media, index, isLast) {
    const hasFile = media.file_name || pendingFiles[index];
    const fileName = pendingFiles[index] ? pendingFiles[index].name : media.file_name;
    const isImage = media.media_type === 'image';
    const previewUrl = media.media_url || (pendingFiles[index] && isImage ? '__pending__' : '');

    let filePreviewHtml;
    if (hasFile) {
      let thumbHtml;
      if (pendingFiles[index] && getFileType(pendingFiles[index].name) === 'image') {
        thumbHtml = `<div class="file-preview__thumb" data-thumb-index="${index}"></div>`;
      } else if (media.media_type === 'image' && media.media_url) {
        thumbHtml = `<div class="file-preview__thumb"><img src="${proxyMediaUrl(media.media_url)}" alt=""></div>`;
      } else {
        thumbHtml = `<div class="file-preview__thumb"><span class="file-preview__video-icon">🎬</span></div>`;
      }

      const fileSize = pendingFiles[index]
        ? formatFileSize(pendingFiles[index].size)
        : '';

      filePreviewHtml = `
        <div class="file-preview">
          ${thumbHtml}
          <div class="file-preview__info">
            <div class="file-preview__name">${escapeHtml(fileName)}</div>
            ${fileSize ? `<div class="file-preview__size">${fileSize}</div>` : ''}
          </div>
        </div>
        <div class="progress-bar hidden" id="progress-${index}">
          <div class="progress-bar__fill" id="progressFill-${index}"></div>
        </div>
      `;
    } else {
      filePreviewHtml = `
        <div class="dropzone" data-drop-index="${index}">
          <span class="dropzone__icon">📁</span>
          <div class="dropzone__text">
            Перетащите файл сюда или <strong>выберите</strong>
          </div>
          <input type="file" class="dropzone__input" data-file-index="${index}" accept="image/*,video/*">
        </div>
      `;
    }

    // Toggle & time row
    let toggleHtml = '';
    if (hasFile) {
      toggleHtml = `
        <div class="toggle-row">
          <label class="toggle-switch">
            <input type="checkbox" data-toggle-index="${index}" ${media.has_schedule ? 'checked' : ''}>
            <span class="toggle-switch__slider"></span>
          </label>
          <span class="toggle-label">⏱ По времени</span>
        </div>
      `;

      if (media.has_schedule) {
        toggleHtml += `
          <div class="time-row">
            <span class="time-row__label">До:</span>
            <input type="time" class="input-time" data-time-index="${index}" value="${media.end_time || ''}">
          </div>
        `;
      }
    }

    return `
      <div class="media-card" data-card-index="${index}">
        <div class="media-card__header">
          <span class="media-card__label">Медиа ${index + 1}</span>
          ${index > 0 || hasFile ? `<button class="media-card__delete" data-delete-index="${index}" title="Удалить">✕</button>` : ''}
        </div>
        ${filePreviewHtml}
        ${toggleHtml}
      </div>
    `;
  }

  // ── Attach Handlers to Media Cards ─────────────────────────
  function attachMediaCardHandlers() {
    // Dropzone click → trigger file input
    document.querySelectorAll('.dropzone').forEach(dz => {
      const idx = parseInt(dz.dataset.dropIndex);
      const input = dz.querySelector('.dropzone__input');

      dz.addEventListener('click', (e) => {
        if (e.target !== input) input.click();
      });

      // Drag & drop
      dz.addEventListener('dragover', (e) => {
        e.preventDefault();
        dz.classList.add('drag-over');
      });
      dz.addEventListener('dragleave', () => {
        dz.classList.remove('drag-over');
      });
      dz.addEventListener('drop', (e) => {
        e.preventDefault();
        dz.classList.remove('drag-over');
        const file = e.dataTransfer.files[0];
        if (file) handleFileSelected(idx, file);
      });

      // File input change
      input.addEventListener('change', (e) => {
        const file = e.target.files[0];
        if (file) handleFileSelected(idx, file);
      });
    });

    // Toggle switches
    document.querySelectorAll('[data-toggle-index]').forEach(toggle => {
      toggle.addEventListener('change', (e) => {
        const idx = parseInt(e.target.dataset.toggleIndex);
        currentSchedule[idx].has_schedule = e.target.checked;

        if (e.target.checked) {
          // If this is the last card and toggle is ON, add a new empty card
          if (idx === currentSchedule.length - 1) {
            currentSchedule.push({
              media_url: '',
              media_type: '',
              file_name: '',
              has_schedule: false,
              end_time: null
            });
          }
        } else {
          // Toggle OFF — remove subsequent cards (with confirmation if they have files)
          currentSchedule[idx].end_time = null;
          if (idx < currentSchedule.length - 1) {
            const hasFilesAfter = currentSchedule.slice(idx + 1).some((m, i) =>
              m.file_name || pendingFiles[idx + 1 + i]
            );
            if (hasFilesAfter) {
              if (!confirm('Удалить все последующие медиа?')) {
                e.target.checked = true;
                currentSchedule[idx].has_schedule = true;
                return;
              }
            }
            // Remove all after current
            currentSchedule = currentSchedule.slice(0, idx + 1);
            // Clean pending files
            Object.keys(pendingFiles).forEach(k => {
              if (parseInt(k) > idx) delete pendingFiles[k];
            });
          }
        }
        renderChainEditor();
      });
    });

    // Time inputs
    document.querySelectorAll('[data-time-index]').forEach(input => {
      input.addEventListener('change', (e) => {
        const idx = parseInt(e.target.dataset.timeIndex);
        currentSchedule[idx].end_time = e.target.value || null;
      });
    });

    // Delete buttons
    document.querySelectorAll('[data-delete-index]').forEach(btn => {
      btn.addEventListener('click', (e) => {
        const idx = parseInt(e.target.dataset.deleteIndex);
        const media = currentSchedule[idx];

        if (media.file_name || pendingFiles[idx]) {
          if (!confirm(`Удалить «${media.file_name || pendingFiles[idx]?.name}»?`)) return;
        }

        // If it's the only card, reset it
        if (currentSchedule.length === 1) {
          currentSchedule[0] = {
            media_url: '', media_type: '', file_name: '',
            has_schedule: false, end_time: null
          };
          delete pendingFiles[0];
        } else {
          currentSchedule.splice(idx, 1);
          // Re-index pending files
          const newPending = {};
          Object.keys(pendingFiles).forEach(k => {
            const ki = parseInt(k);
            if (ki < idx) newPending[ki] = pendingFiles[ki];
            else if (ki > idx) newPending[ki - 1] = pendingFiles[ki];
          });
          pendingFiles = newPending;
        }
        renderChainEditor();
      });
    });

    // Generate thumbnails for pending image files
    document.querySelectorAll('[data-thumb-index]').forEach(thumb => {
      const idx = parseInt(thumb.dataset.thumbIndex);
      const file = pendingFiles[idx];
      if (file && getFileType(file.name) === 'image') {
        const reader = new FileReader();
        reader.onload = (e) => {
          thumb.innerHTML = `<img src="${e.target.result}" alt="">`;
        };
        reader.readAsDataURL(file);
      }
    });
  }

  // ── Handle File Selection ──────────────────────────────────
  async function handleFileSelected(index, file) {
    const type = getFileType(file.name);
    if (!type) {
      showToast('Неподдерживаемый формат файла', 'error');
      return;
    }

    // Video size limit: 100 MB
    if (type === 'video' && file.size > 100 * 1024 * 1024) {
      showToast('Видео не должно превышать 100 МБ', 'warning');
      return;
    }

    let processedFile = file;

    // Compress images automatically
    if (type === 'image') {
      showToast('Оптимизация изображения…', 'success');
      processedFile = await compressImage(file);
    }

    pendingFiles[index] = processedFile;
    currentSchedule[index].file_name = processedFile.name;
    currentSchedule[index].media_type = type;

    renderChainEditor();
  }

  // ── Save Schedule ──────────────────────────────────────────
  async function saveSchedule() {
    const saveBtn = document.getElementById('btnSaveSchedule');
    saveBtn.disabled = true;
    saveBtn.textContent = 'Сохранение…';

    try {
      // Validate time order
      const timeValidation = validateTimeOrder();
      if (timeValidation === 'missing') {
        showToast('Укажите время «До» для всех активных переключателей', 'error');
        saveBtn.disabled = false;
        saveBtn.textContent = '💾 Сохранить';
        return;
      }
      if (timeValidation === 'order') {
        showToast('Время «До» должно идти по возрастанию', 'error');
        saveBtn.disabled = false;
        saveBtn.textContent = '💾 Сохранить';
        return;
      }

      // Filter out empty media cards (no file and no url)
      const validSchedule = currentSchedule.filter((m, i) =>
        m.media_url || pendingFiles[i]
      );

      if (validSchedule.length === 0) {
        // Save empty schedule
        await saveToFirestore([]);
        showToast('Расписание очищено');
        closeEditor();
        return;
      }

      // Upload pending files
      const finalSchedule = [];

      for (let i = 0; i < currentSchedule.length; i++) {
        const media = currentSchedule[i];
        if (!media.media_url && !pendingFiles[i]) continue;

        let mediaUrl = media.media_url;

        if (pendingFiles[i]) {
          // Загрузка напрямую в Firebase Storage (без лимита размера)
          const file = pendingFiles[i];
          const timestamp = Date.now();
          const safeName = file.name.replace(/[^a-zA-Z0-9._-]/g, '_');
          const storagePath = `media/${locationId}/slot_${currentSlot}/${timestamp}_${safeName}`;

          // Показываем прогресс-бар
          const progressBar = document.getElementById(`progress-${i}`);
          const progressFill = document.getElementById(`progressFill-${i}`);
          if (progressBar) progressBar.classList.remove('hidden');

          try {
            mediaUrl = await uploadToStorage(file, storagePath, (percent) => {
              if (progressFill) progressFill.style.width = percent + '%';
            });
          } catch (uploadErr) {
            // Фоллбэк: если прямая загрузка не удалась, пробуем через Cloud Function
            console.warn('[Teliki] Direct upload failed, trying proxy:', uploadErr.message);
            if (progressFill) progressFill.style.width = '10%';

            const uploadResp = await fetch(`${API_BASE}?upload=${encodeURIComponent(storagePath)}`, {
              method: 'POST',
              headers: { 'Content-Type': file.type || 'application/octet-stream' },
              body: file
            });

            if (!uploadResp.ok) {
              const errData = await uploadResp.json().catch(() => ({}));
              throw new Error(errData.error || 'Upload failed');
            }

            const result = await uploadResp.json();
            mediaUrl = result.url || result.publicUrl;
          }

          if (progressFill) progressFill.style.width = '100%';
        }

        finalSchedule.push({
          media_url: mediaUrl,
          media_type: media.media_type,
          file_name: media.file_name,
          has_schedule: media.has_schedule,
          end_time: media.has_schedule ? media.end_time : null
        });
      }

      await saveToFirestore(finalSchedule);
      showToast('Расписание сохранено');
      closeEditor();

    } catch (err) {
      console.error('[Teliki] Save error:', err);
      showToast('Ошибка при сохранении: ' + err.message, 'error');
      saveBtn.disabled = false;
      saveBtn.textContent = '💾 Сохранить';
    }
  }

  // ── Save to Firestore (via proxy) ─────────────────────────
  async function saveToFirestore(schedule) {
    const docPath = `locations/${locationId}/screens/slot_${currentSlot}`;
    const resp = await fetch(`${API_BASE}?write=${encodeURIComponent(docPath)}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        schedule: schedule,
        slot_number: currentSlot,
        updated_at: '__SERVER_TIMESTAMP__'
      })
    });
    if (!resp.ok) {
      const errData = await resp.json().catch(() => ({}));
      throw new Error(errData.error || 'Write failed');
    }
  }

  // ── Validate Time Order ────────────────────────────────────
  function validateTimeOrder() {
    let lastTime = null;
    for (const media of currentSchedule) {
      if (!media.has_schedule) continue;
      if (!media.end_time) return 'missing'; // Toggle ON but no time picked
      if (lastTime && media.end_time <= lastTime) return 'order'; // Wrong order
      lastTime = media.end_time;
    }
    return 'ok';
  }

  // ── Close Editor ───────────────────────────────────────────
  function closeEditor() {
    currentSlot = null;
    currentSchedule = [];
    pendingFiles = {};
    chainEditorContainer.innerHTML = '';
    document.querySelectorAll('.slot-card').forEach(c => c.classList.remove('active'));
    loadSlots();
  }

  // ── Image Compression ─────────────────────────────────────
  const MAX_WIDTH  = 3840; // 4K TV width
  const MAX_HEIGHT = 2160; // 4K TV height
  const MAX_FILE_SIZE = 1.5 * 1024 * 1024; // 1.5 МБ

  function compressImage(file) {
    return new Promise((resolve) => {
      // Skip non-image formats
      const ext = file.name.split('.').pop().toLowerCase();
      if (['svg', 'gif'].includes(ext)) {
        resolve(file);
        return;
      }

      // Файл ≤ 1.5 МБ — загружаем оригинал без изменений
      if (file.size <= MAX_FILE_SIZE) {
        console.log(`[Teliki] ${formatFileSize(file.size)} — оригинал`);
        resolve(file);
        return;
      }

      // Файл > 1.5 МБ — только ресайз до 4K, сохраняем как JPEG
      const img = new Image();
      const url = URL.createObjectURL(file);

      img.onload = () => {
        URL.revokeObjectURL(url);

        let { width, height } = img;

        // Ресайз только если больше 4K
        if (width > MAX_WIDTH || height > MAX_HEIGHT) {
          const ratio = Math.min(MAX_WIDTH / width, MAX_HEIGHT / height);
          width = Math.round(width * ratio);
          height = Math.round(height * ratio);
        }

        const canvas = document.createElement('canvas');
        canvas.width = width;
        canvas.height = height;
        const ctx = canvas.getContext('2d');
        ctx.drawImage(img, 0, 0, width, height);

        // Сохраняем как JPEG 95% — без конвертации в WebP
        canvas.toBlob((blob) => {
          if (!blob || blob.size >= file.size) {
            // Если сжатие не помогло, загружаем оригинал
            resolve(file);
            return;
          }
          const newName = file.name.replace(/\.[^.]+$/, '.jpg');
          const result = new File([blob], newName, { type: 'image/jpeg' });
          console.log(`[Teliki] Ресайз: ${formatFileSize(file.size)} → ${formatFileSize(result.size)}`);
          resolve(result);
        }, 'image/jpeg', 0.95);
      };

      img.onerror = () => {
        URL.revokeObjectURL(url);
        resolve(file);
      };

      img.src = url;
    });
  }

  // ── Utility Functions ──────────────────────────────────────
  function getFileType(filename) {
    const ext = filename.split('.').pop().toLowerCase();
    const imageExts = ['jpg', 'jpeg', 'png', 'gif', 'webp', 'bmp', 'svg'];
    const videoExts = ['mp4', 'webm', 'mov', 'avi', 'mkv'];
    if (imageExts.includes(ext)) return 'image';
    if (videoExts.includes(ext)) return 'video';
    return null;
  }

  function formatFileSize(bytes) {
    if (bytes < 1024) return bytes + ' Б';
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' КБ';
    return (bytes / (1024 * 1024)).toFixed(1) + ' МБ';
  }

  function escapeHtml(str) {
    const div = document.createElement('div');
    div.textContent = str;
    return div.innerHTML;
  }

  // ── Direct Firebase Storage Upload (через signed URL) ───
  // 1. Получаем signed URL от Cloud Function (маленький запрос)
  // 2. Грузим файл напрямую в Storage (без лимита, с прогрессом)
  async function uploadToStorage(file, storagePath, onProgress) {
    // Шаг 1: получаем signed URL
    const ct = file.type || 'application/octet-stream';
    const signResp = await fetch(
      `${API_BASE}?signedUpload=${encodeURIComponent(storagePath)}&contentType=${encodeURIComponent(ct)}`
    );
    if (!signResp.ok) throw new Error('Не удалось получить ссылку для загрузки');
    const { uploadUrl } = await signResp.json();

    // Шаг 2: загружаем файл напрямую с реальным прогрессом
    await new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      xhr.open('PUT', uploadUrl);
      xhr.setRequestHeader('Content-Type', ct);

      xhr.upload.onprogress = (e) => {
        if (e.lengthComputable && onProgress) {
          onProgress(Math.round((e.loaded / e.total) * 100));
        }
      };

      xhr.onload = () => {
        if (xhr.status >= 200 && xhr.status < 300) resolve();
        else reject(new Error(`Upload HTTP ${xhr.status}`));
      };
      xhr.onerror = () => reject(new Error('Ошибка сети при загрузке'));
      xhr.ontimeout = () => reject(new Error('Таймаут загрузки'));
      xhr.timeout = 600000; // 10 минут

      xhr.send(file);
    });

    // Шаг 3: получаем download URL
    const dlResp = await fetch(
      `${API_BASE}?downloadUrl=${encodeURIComponent(storagePath)}`
    );
    if (!dlResp.ok) throw new Error('Не удалось получить ссылку на файл');
    const { url } = await dlResp.json();
    return url;
  }

  // ── Expose close for inline onclick ────────────────────────
  window.__teliki = { closeEditor };

  // ── Deploy to TVs ─────────────────────────────────────────
  async function deployToTVs() {
    btnDeploy.disabled = true;
    btnDeploy.textContent = '⏳ Выгружаю...';

    try {
      // Обновляем updated_at на всех экранах этой локации
      const resp = await fetch(`${API_BASE}?path=locations/${locationId}/screens`);
      if (!resp.ok) throw new Error('Не удалось получить экраны');
      const json = await resp.json();
      const docs = json.documents || [];

      for (const doc of docs) {
        const docId = doc.name.split('/').pop();
        const docPath = `locations/${locationId}/screens/${docId}`;
        await fetch(`${API_BASE}?write=${encodeURIComponent(docPath)}`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ updated_at: '__SERVER_TIMESTAMP__' })
        });
      }

      showToast('Выгружено! ТВ обновятся в течение 10 минут', 'success');
    } catch (err) {
      console.error('[Teliki] Deploy error:', err);
      showToast('Ошибка выгрузки: ' + err.message, 'error');
    }

    btnDeploy.disabled = false;
    btnDeploy.textContent = '📡 Выгрузить на телевизоры';
  }

  btnDeploy.addEventListener('click', deployToTVs);

  // ── Init ───────────────────────────────────────────────────
  async function init() {
    const exists = await loadLocationName();
    if (exists) {
      loadSlots();
    }
  }
  init();

})();
