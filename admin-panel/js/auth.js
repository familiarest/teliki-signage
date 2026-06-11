/**
 * Teliki — Simple Auth Gate
 * Пароль проверяется клиентски через SHA-256 хэш.
 * Сессия сохраняется в localStorage (не теряется при закрытии вкладки).
 */

const TELIKI_AUTH_KEY  = 'teliki_authenticated';
// SHA-256 от пароля 'kava2026' — пароль не хранится в коде напрямую
const TELIKI_PASS_HASH = '1fbac4cbb8d8eb44139f60d5b3ec171c3e116320315580b49f9593dff4d5cc9d';

async function sha256(message) {
  const msgBuffer = new TextEncoder().encode(message);
  const hashBuffer = await crypto.subtle.digest('SHA-256', msgBuffer);
  const hashArray  = Array.from(new Uint8Array(hashBuffer));
  return hashArray.map(b => b.toString(16).padStart(2, '0')).join('');
}

(function initAuth() {
  const isAuth    = localStorage.getItem(TELIKI_AUTH_KEY) === 'true';
  const overlay   = document.getElementById('authOverlay');
  const appContent = document.getElementById('appContent');

  if (!overlay || !appContent) return;

  if (isAuth) {
    overlay.style.display  = 'none';
    appContent.style.display = '';
    return;
  }

  // Show auth, hide app
  overlay.style.display  = 'flex';
  appContent.style.display = 'none';

  const form  = document.getElementById('authForm');
  const input = document.getElementById('authPassword');
  const error = document.getElementById('authError');

  if (form) {
    form.addEventListener('submit', async (e) => {
      e.preventDefault();
      const val = input.value.trim();

      const hash = await sha256(val);
      if (hash === TELIKI_PASS_HASH) {
        localStorage.setItem(TELIKI_AUTH_KEY, 'true');
        overlay.style.opacity   = '1';
        overlay.style.transition = 'opacity 0.3s ease';
        overlay.style.opacity   = '0';
        setTimeout(() => {
          overlay.style.display   = 'none';
          appContent.style.display = '';
          appContent.style.opacity = '0';
          appContent.style.transition = 'opacity 0.3s ease';
          requestAnimationFrame(() => { appContent.style.opacity = '1'; });
        }, 300);
      } else {
        error.textContent = 'Неверный пароль';
        error.style.display = 'block';
        input.classList.add('input-error');
        input.value = '';
        input.focus();
        setTimeout(() => {
          error.style.display = 'none';
          input.classList.remove('input-error');
        }, 2500);
      }
    });
  }

  // Focus password field
  if (input) {
    setTimeout(() => input.focus(), 100);
  }
})();
