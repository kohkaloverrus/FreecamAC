# Публикация на GitHub — kohkaloverrus

## Быстрый путь (без git на вашем ПК)
1. Откройте https://github.com/new
2. Repository name: `xray-freecam-anticheat` → Create repository (пустой!)
3. На странице репо: **Add file → Upload files**
4. Перетащите ВСЕ файлы из папки `download/`:
   - `xray-freecam-anticheat-1.0.0.jar`
   - `xray-freecam-anticheat-v1.0.0.zip`
   - `xray-freecam-anticheat-all.bundle`
5. Commit files → готово, скачивание:
   `https://github.com/kohkaloverrus/xray-freecam-anticheat/raw/main/download/xray-freecam-anticheat-1.0.0.jar`

## Полный путь (код + история + автосборка CI)
```bash
git clone xray-freecam-anticheat-all.bundle my-repo
cd my-repo
git remote add origin https://github.com/kohkaloverrus/xray-freecam-anticheat.git
git push -u origin master
git tag v1.0.0
git push origin v1.0.0   # CI сам создаст Release с JAR
```
