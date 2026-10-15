# Публикация на GitHub — 2 простых способа

## Способ 1: Через браузер (без командной строки)
1. Откройте https://github.com/new
2. Repository name: `xray-freecam-anticheat`
3. НЕ ставьте галочки README/.gitignore/license → Create repository
4. На странице пустого репозитория нажмите ссылку **"uploading an existing file"**
5. Перетащите файл `download/anticheat-github-upload.zip` — GitHub сам распакует его
   (если не распакует — распакуйте архив двойным кликом и перетащите содержимое папки)
6. Нажмите зелёную кнопку **Commit changes** (внизу страницы справа)

## Способ 2: Git из терминала (на вашем компьютере)
```bash
git clone download/anticheat-repo.bundle xray-freecam-anticheat
cd xray-freecam-anticheat
git remote add origin https://github.com/kohkaloverrus/xray-freecam-anticheat.git
git push -u origin master
git push origin v1.0.0   # тег -> CI сам создаст Release с JAR
```

## Release со скачиванием
После публикации: вкладка Releases → Draft new release → tag `v1.0.0` →
перетащите `xray-freecam-anticheat-1.0.0.jar` и `xray-freecam-anticheat-v1.0.0.zip` → Publish release.
