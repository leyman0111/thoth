# Деплой Thoth

Тот же сервер и та же схема, что у loco-back: Docker Compose за nginx хоста, образ в GHCR, деплой по SSH из GitHub Actions.

```
PR в main      ──► test (mvn verify + PostgreSQL в service-контейнере)
push в main    ──► test ──► build (ghcr.io/leyman0111/thoth:<sha12> + latest) ──► deploy (/opt/thoth)
ручной запуск  ──► с image_tag: только deploy этого тега (откат/редеплой); без него — как push
```

Деплой проходит только когда контейнер стал `healthy` по `/actuator/health`, иначе job падает и показывает логи.

## Соседство с loco-back

|              | loco-back       | thoth                                   |
|--------------|-----------------|-----------------------------------------|
| каталог      | `/opt/app`      | `/opt/thoth`                            |
| compose name | `loco`          | `thoth`                                 |
| приложение   | 127.0.0.1:8080  | 127.0.0.1:8081, лимит памяти 400 МБ     |
| PostgreSQL   | 127.0.0.1:5433  | **та же**, база `thoth`, роль `thoth`   |

На сервере около 1 ГБ RAM, второй PostgreSQL не помещается. Поэтому thoth подключается к сети `loco_default`
и ходит в `db:5432` контейнера loco-back. База `thoth` принадлежит роли `thoth`, к данным loco у неё доступа нет.
Базу и роль создаёт (и обновляет пароль) workflow **Provision thoth server**.

Следствия:
- thoth не работает, пока не запущен loco-back (без БД приложение падает и перезапускается).
- Бэкап `pgdata` loco-back включает и данные thoth.
- `docker compose down -v` в `/opt/app` удалит и базу thoth.

## Первый запуск

1. **Секреты** (repo → Settings → Secrets and variables → Actions → *Secrets*):

   | Секрет | Откуда |
   |---|---|
   | `SERVER_HOST`, `SERVER_USER`, `SERVER_SSH_KEY`, `SERVER_PORT` (необяз.) | те же значения, что в loco-back (секреты привязаны к репозиторию, их нужно завести заново) |
   | `DB_PASSWORD` | новый случайный: `openssl rand -base64 24` (без `'`) |
   | `TELEGRAM_BOT_TOKEN` | @BotFather |
   | `OPENROUTER_API_KEY` | openrouter.ai |

   *Variables* → `THOTH_WEBAPP_URL` = `https://<домен>` — адрес, который откроют кнопки бота.

2. **Actions → Provision thoth server → Run workflow** — создаст `/opt/thoth/.env`.
3. **Actions → Deploy thoth → Run workflow** (или push в main).
4. **nginx + HTTPS** — шаблон `deploy/nginx/thoth.conf` → `/etc/nginx/conf.d/thoth.conf`, затем
   `sudo certbot --nginx -d <домен>` и `sudo nginx -t && sudo systemctl reload nginx`.
   SELinux-флаг `httpd_can_network_connect` уже включён provision'ом loco-back.
5. В @BotFather для бота задать домен Mini App (`/setdomain` или через Bot Settings → Configure Mini App).

Секреты поменялись → снова **Provision**, затем **Deploy** с `image_tag=latest`.
`DB_PASSWORD` можно менять: Provision выставит роли `thoth` новый пароль.

## Важно про бота

Бот получает обновления long polling'ом: с одним токеном может работать только один экземпляр.
Пока прод запущен, локально запускайте приложение с `THOTH_BOT_ENABLED=false` или с другим (тестовым) ботом.

## Откат

Actions → Deploy thoth → Run workflow → `image_tag` = 12 символов SHA рабочего коммита.

## На сервере

```bash
cd /opt/thoth
docker compose ps
docker compose logs -f app
# БД с ноутбука: ssh -L 5433:127.0.0.1:5433 <user>@<server>  → localhost:5433, база thoth, thoth / DB_PASSWORD
```
