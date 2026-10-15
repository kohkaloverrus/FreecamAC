# Как выложить на GitHub

## Быстрый способ (если код «не виден» на GitHub)
Весь репозиторий упакован в один файл **`xray-freecam-anticheat.bundle`** (в корне проекта).
Он содержит ВСЁ: исходный код, историю коммитов, README, CI. Его можно передать куда угодно
(флешка, мессенджер, другой ПК) и восстановить полноценный git-репозиторий:

```bash
git clone xray-freecam-anticheat.bundle my-repo   # или: git unbundle
cd my-repo
git remote add origin https://github.com/<ВАШ_ЛОГИН>/<РЕПО>.git
git push -u origin master
```

Готовый плагин (JAR) лежит рядом: `target/xray-freecam-anticheat-1.0.0.jar` —
его можно прикрепить к GitHub Release вручную (Drag & Drop на странице Create a new release).

Репозиторий полностью готов к публикации. Выполните эти шаги локально (или дайте токен — сделаю сам):

## 1. Создайте пустой публичный репозиторий на github.com (без README)

## 2. Добавьте remote и отправьте
```bash
git remote add origin https://github.com/<ВАШ_ЛОГИН>/XRayFreecamAntiCheat.git
git branch -M main
git push -u origin main
```

## 3. Альтернатива с токеном (для push из скрипта/CI)
```bash
git remote add origin https://x-access-token:GITHUB_TOKEN@github.com/<ВАШ_ЛОГИН>/XRayFreecamAntiCheat.git
git push -u origin main
```
Токен: GitHub → Settings → Developer settings → Fine-grained tokens → разрешения "Contents: Read and write".

## 4. Публикация jar через Release
При пуше тега `v*` (например `git tag v1.0.0 && git push origin v1.0.0`)
GitHub Actions (`.github/workflows/release.yml`) соберёт проект и создаст Release с jar-файлом.

## 5. После первого пуша
Замените `YOUR_USERNAME` в бейджах README.md на ваш логин.
