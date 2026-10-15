# Публикация на GitHub

Локальный git-репозиторий готов: 4 коммита, вся история и код.
Осталось только создать репозиторий на github.com и запушить.

## Быстрый способ (одна команда после создания репозитория)
```bash
git remote add origin https://github.com/<ЛОГИН>/<РЕПО>.git
git push -u origin master
```

## Если нет доступа к папке /workspace — используйте bundle
Файл `xray-freecam-anticheat-all.bundle` содержит ВЕСЬ репозиторий с историей:
```bash
git clone xray-freecam-anticheat-all.bundle my-repo
cd my-repo
git remote add origin https://github.com/<ЛОГИН>/<РЕПО>.git
git push -u origin master
```

## Создание репозитория на GitHub
1. https://github.com/new
2. Имя: `xray-freecam-anticheat`, видимость любая, **без** README/.gitignore/license (иначе истории разойдутся).
3. Скопируйте выданный URL и выполните команды выше.

## Без git-клиента (web-upload)
Создайте пустой репозиторий → Add file → Upload files → перетащите содержимое проекта
(файлы из списка ниже, папку `target` не нужно):
- pom.xml, README.md, LICENSE, CONTRIBUTING.md, SECURITY.md, .gitignore
- src/main/java/com/anticheat/xrayfreecam/*.java (+ listeners/)
- src/main/resources/plugin.yml, config.yml
- .github/workflows/build.yml, release.yml; .github/ISSUE_TEMPLATE/*, PULL_REQUEST_TEMPLATE.md

После пуша workflow build.yml соберёт JAR автоматически.
