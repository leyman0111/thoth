# Деплой Thoth

Отдельный сервер (AlmaLinux), Docker Compose за nginx хоста, образ в GHCR, деплой по SSH из GitHub Actions.

```
PR в main      ──► test (mvn verify + PostgreSQL в service-контейнере)
push в main    ──► test ──► build (ghcr.io/leyman0111/thoth:<sha12> + latest) ──► deploy (/opt/thoth)
ручной запуск  ──► с image_tag: только deploy этого тега (откат/редеплой); без него — как push
```

Деплой проходит только когда контейнер стал `healthy` по `/actuator/health`, иначе job падает и показывает логи.

## Что где на сервере

| | |
|---|---|
| каталог | `/opt/thoth` (`.env`, `docker-compose.yml`), владелец `deploy` |
| приложение | `127.0.0.1:8080`, лимит памяти 512 МБ |
| PostgreSQL 17 | `127.0.0.1:5432`, лимит 320 МБ, том `thoth_pgdata` |
| пользователи | `ilnur` — администратор (sudo с паролем), `deploy` — CI/CD (только ключ, группа docker, без sudo) |

Лимиты рассчитаны на 2 ГБ RAM. Для другого сервера — переменные репозитория `APP_MEM_LIMIT` / `DB_MEM_LIMIT`
(например `768m` / `512m` для 4 ГБ), затем Provision и Deploy.

## Первый запуск

### 1. Сервер (один раз, от root)

`deploy/setup-users.sh`: обновление системы, Docker CE, пользователи `ilnur` и `deploy` с SSH-ключами,
`/opt/thoth`, запрет входа root и по паролю, firewalld (ssh/http/https), SELinux-флаг для nginx.

```bash
# на маке: ключи
ssh-keygen -t ed25519 -C "ilnur@macbook" -f ~/.ssh/thoth_ilnur
ssh-keygen -t ed25519 -C "github-actions-thoth" -f ~/.ssh/thoth_deploy -N ""
# вставить содержимое обоих .pub в начало скрипта, затем
scp deploy/setup-users.sh root@<IP>:
ssh -t root@<IP> bash setup-users.sh
```

Проверить в **новом** окне `ssh -t -i ~/.ssh/thoth_ilnur ilnur@<IP> 'sudo -v && echo ok'` и
`ssh -i ~/.ssh/thoth_deploy deploy@<IP> docker ps`, и только потом в окне root `systemctl reload sshd`.

### 2. Секреты и переменные GitHub

Repo → Settings → Secrets and variables → Actions.

| Секрет | Значение |
|---|---|
| `SERVER_HOST` | IP сервера |
| `SERVER_USER` | `deploy` |
| `SERVER_SSH_KEY` | содержимое `~/.ssh/thoth_deploy` (приватный ключ) |
| `SERVER_PORT` | необязательно, если не 22 |
| `DB_PASSWORD` | `openssl rand -base64 24` (без `'`) |
| `TELEGRAM_BOT_TOKEN` | @BotFather |
| `OPENROUTER_API_KEY` | openrouter.ai |

| Переменная | Значение |
|---|---|
| `THOTH_WEBAPP_URL` | `https://<домен>` — адрес, который откроют кнопки бота |
| `APP_MEM_LIMIT`, `DB_MEM_LIMIT` | необязательно, по умолчанию `512m` / `320m` |

### 3. Actions

1. **Provision thoth server** → Run workflow — пишет `/opt/thoth/.env` (sudo не нужен).
2. **Deploy thoth** → Run workflow (или push в main).

### 4. nginx + HTTPS (от ilnur)

```bash
sudo dnf -y install nginx certbot python3-certbot-nginx
sudo cp thoth.conf /etc/nginx/conf.d/thoth.conf     # из deploy/nginx/, домен поправить
sudo systemctl enable --now nginx
sudo certbot --nginx -d <домен>
```

Затем в @BotFather задать домен Mini App.

## Секреты поменялись

Снова **Provision**, затем **Deploy** с `image_tag=latest`.
`DB_PASSWORD` postgres применяет только при первой инициализации тома; для существующей БД:
`docker compose exec db psql -U thoth -c "ALTER ROLE thoth PASSWORD '<новый>'"`.

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
docker stats --no-stream
# БД с ноутбука: ssh -L 5432:127.0.0.1:5432 -i ~/.ssh/thoth_ilnur ilnur@<IP> → localhost:5432, thoth / DB_PASSWORD
```
