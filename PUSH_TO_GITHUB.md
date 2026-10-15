# Публикация на GitHub (аккаунт: kohkaloverrus)

## Способ 1 — загрузка через браузер (без git, самый простой)
GitHub сам разберёт zip-архив с папками:
1. Зайдите на https://github.com/new
2. Repository name: `xray-freecam-anticheat`, отметьте **Add README? НЕТ** (он уже в архиве), Create repository
3. На странице репозитория: **Add file → Upload files**
4. Перетащите ОДИН файл: `download/anticheat-github-upload.zip` (GitHub распакует его и создаст все папки)
   - Если не распаковал — распакуйте архив у себя и перетащите все файлы и папки из него
5. Commit changes (кнопка внизу справа)

## Способ 2 — через git + токен
Токен: https://github.com/settings/tokens → "Generate new token (classic)" → галочка **repo** → скопируйте.
```bash
git clone anticheat-repo.bundle xray-freecam-anticheat
cd xray-freecam-anticheat
git remote add origin https://kohkaloverrus:<ВАШ_ТОКЕН>@github.com/kohkaloverrus/xray-freecam-anticheat.git
git push -u origin master
git push origin v1.0.0        # тег -> CI сам создаст Release с JAR
```

## После публикации — Release со скачиванием
- Если пушнули тег v1.0.0 (способ 2): дождитесь зелёной галочки Actions → Release появится сам.
- Иначе вручную: вкладка **Releases → Draft a new release** → tag `v1.0.0` → Attach binaries: загрузите `xray-freecam-anticheat-1.0.0.jar` и `xray-freecam-anticheat-v1.0.0.zip` → Publish.

Готовые файлы всегда лежат в папке `download/`.
