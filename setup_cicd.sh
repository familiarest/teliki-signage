#!/bin/bash
# ============================================================
# Teliki — Настройка GitHub CI/CD (один раз)
# После этого VPN НИКОГДА не нужен для деплоя.
# ============================================================

set -e

NODE="/Users/azizametov/Library/Caches/ms-playwright-go/1.57.0/node"
FIREBASE="$HOME/.npm/_npx/7750544ccf494d8b/node_modules/firebase-tools/lib/bin/firebase.js"
PROJECT_DIR="$HOME/Downloads/ПРИЛОЖЕНИЕ ДЛЯ ТЕЛИКОВ"

echo ""
echo "=========================================="
echo "  Teliki — Настройка CI/CD"
echo "=========================================="
echo ""

# Шаг 1: Получить Firebase CI токен
echo "Шаг 1: Получение Firebase токена..."
echo "Откроется браузер — залогинься в Google."
echo ""

FIREBASE_TOKEN=$($NODE $FIREBASE login:ci 2>/dev/null | grep "1//" || true)

if [ -z "$FIREBASE_TOKEN" ]; then
    echo ""
    echo "Скопируй токен из вывода выше (строка начинающаяся с 1//)"
    echo "и вставь сюда:"
    read -r FIREBASE_TOKEN
fi

echo ""
echo "✅ Токен получен: ${FIREBASE_TOKEN:0:20}..."
echo ""

# Шаг 2: Создать GitHub репозиторий
echo "Шаг 2: Создание GitHub репозитория..."
echo ""

if ! command -v gh &>/dev/null; then
    echo "Устанавливаю GitHub CLI..."
    brew install gh 2>/dev/null || true
fi

# Логин в GitHub
gh auth status 2>/dev/null || gh auth login

# Создание репо
cd "$PROJECT_DIR"
gh repo create teliki-signage --public --source=. --remote=origin --push 2>/dev/null || {
    echo "Репо уже существует или ошибка. Попробуй вручную:"
    echo "  gh repo create teliki-signage --public --source=. --remote=origin --push"
}

# Шаг 3: Добавить Firebase Token как секрет
echo ""
echo "Шаг 3: Добавление секрета FIREBASE_TOKEN..."
echo "$FIREBASE_TOKEN" | gh secret set FIREBASE_TOKEN

echo ""
echo "=========================================="
echo "  ✅ ВСЁ ГОТОВО!"
echo "=========================================="
echo ""
echo "Теперь для деплоя просто делай:"
echo "  cd ~/Downloads/ПРИЛОЖЕНИЕ\ ДЛЯ\ ТЕЛИКОВ"
echo "  git add -A && git commit -m 'update' && git push"
echo ""
echo "GitHub сам задеплоит на Firebase. VPN не нужен."
echo ""
