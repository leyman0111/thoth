#!/usr/bin/env bash
# Первичная настройка сервера thoth (AlmaLinux 9/10).
# Запуск от root:  scp deploy/setup-users.sh root@<IP>: && ssh -t root@<IP> bash setup-users.sh
# Повторный запуск безопасен.
set -euo pipefail

# ==== ВСТАВЬТЕ ПУБЛИЧНЫЕ КЛЮЧИ (содержимое .pub-файлов, одной строкой) ====
ILNUR_PUBKEY='ssh-ed25519 AAAA... ilnur@macbook'
DEPLOY_PUBKEY='ssh-ed25519 AAAA... github-actions-thoth'
# ==========================================================================
APP_DIR=/opt/thoth

[ "$(id -u)" -eq 0 ] || { echo "Запустите от root"; exit 1; }
case "$ILNUR_PUBKEY $DEPLOY_PUBKEY" in *"AAAA..."*) echo "Сначала вставьте публичные ключи в начало скрипта"; exit 1;; esac

add_key() {
  local user=$1 key=$2 home f
  home="$(getent passwd "$user" | cut -d: -f6)"
  install -d -m 700 -o "$user" -g "$user" "$home/.ssh"
  f="$home/.ssh/authorized_keys"
  touch "$f"
  grep -qxF "$key" "$f" || echo "$key" >> "$f"
  chown "$user:$user" "$f"; chmod 600 "$f"
  restorecon -R "$home/.ssh" 2>/dev/null || true   # SELinux-метки, иначе sshd не прочитает ключи
}

echo "== [1/5] Обновление системы и Docker CE"
dnf -y upgrade --refresh
if ! command -v docker >/dev/null 2>&1; then
  dnf -y remove podman buildah runc 2>/dev/null || true
  dnf -y install dnf-plugins-core
  dnf config-manager --add-repo https://download.docker.com/linux/rhel/docker-ce.repo
  dnf -y install docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
fi
systemctl enable --now docker
docker version --format 'Docker {{.Server.Version}}'

echo "== [2/5] Пользователь ilnur (администратор, sudo с паролем)"
id ilnur >/dev/null 2>&1 || useradd -m -s /bin/bash ilnur
usermod -aG wheel,docker ilnur
add_key ilnur "$ILNUR_PUBKEY"
if passwd -S ilnur | grep -qE '^ilnur (LK|L|NP) '; then
  echo "Задайте пароль ilnur — он нужен для sudo (по SSH вход только по ключу):"
  passwd ilnur
fi

echo "== [3/5] Пользователь deploy (CI/CD: только ключ, без пароля и без sudo)"
id deploy >/dev/null 2>&1 || useradd -m -s /bin/bash deploy
usermod -aG docker deploy
add_key deploy "$DEPLOY_PUBKEY"
install -d -m 750 -o deploy -g deploy "$APP_DIR"

echo "== [4/5] SSH: только ключи, root запрещён"
# 00- — чтобы переопределить настройки из 01-permitrootlogin.conf / 50-cloud-init.conf
cat > /etc/ssh/sshd_config.d/00-hardening.conf <<'EOF'
PermitRootLogin no
PasswordAuthentication no
KbdInteractiveAuthentication no
PubkeyAuthentication yes
AllowUsers ilnur deploy
EOF
sshd -t

echo "== [5/5] firewalld (ssh, http, https) и SELinux для nginx"
if command -v getenforce >/dev/null 2>&1 && [ "$(getenforce)" != "Disabled" ]; then
  setsebool -P httpd_can_network_connect 1   # nginx → 127.0.0.1:8080
fi
if systemctl is-active --quiet firewalld; then
  firewall-cmd --permanent --add-service=ssh --add-service=http --add-service=https >/dev/null
  firewall-cmd --reload >/dev/null
  firewall-cmd --list-services
fi

cat <<EOF

Готово. sshd ещё НЕ перезапущен — сначала проверьте вход в НОВОМ окне терминала:
  ssh ilnur@<IP>            затем  sudo -v
  ssh -i ~/.ssh/thoth_deploy deploy@<IP> docker ps
Если оба входа работают, примените настройки SSH (в этом же окне root):
  systemctl reload sshd
EOF
