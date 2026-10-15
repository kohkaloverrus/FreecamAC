# Публикация PR в FreecamAC (kohkaloverrus/FreecamAC)

Файл для публикации: `download/freecamac-pr3.bundle` (80 КБ, содержит 1 чистый коммит).

## Способ A — через браузер (без командной строки)
1. Откройте https://github.com/kohkaloverrus/FreecamAC/compare
2. **Base:** `main`, **Compare:** выберите ветку `minecraft-xray-freecam-anti-cheat-94ae9`
   (это та же ветка, что у закрытого PR #2 — GitHub покажет «This branch is ahead of main»)
3. Нажмите **«Open pull request»** → зелёная кнопка **«Create pull request»**
4. В описании PR нажмите **«Attach files»** и перетащите:
   - `download/xray-freecam-anticheat-1.0.0.jar` (готовый плагин Paper 1.21.11 / Java 25)
   - `download/xray-freecam-anticheat-v1.0.0.zip` (JAR + config.yml)
5. После создания PR нажмите **«Merge pull request»** — код окажется в main,
   а workflow release.yml опубликует Release v1.0.0 с JAR для скачивания.

## Способ B — через терминал (git)
```bash
git clone freecamac-pr3.bundle ac && cd ac
git remote add origin https://github.com/kohkaloverrus/FreecamAC.git
git push origin minecraft-xray-freecam-anti-cheat-94ae9
# затем откройте compare и создайте PR (кнопка появится сама)
```

## Что внутри PR
- Полный исходный код античита (5 классов): защита от X-Ray/Freecam, спектаторы НЕ получают камень
- Сборка под **Paper 1.21.11 + Java 25** (проверено на живом сервере, ошибок нет)
- `download/` — готовый JAR и zip релиза для скачивания прямо из репозитория
