# eVTOL 后端部署手册（香港 Ubuntu 24.04 · 与农机通共存）

本手册把 eVTOL FastAPI 后端以 **systemd 服务 + 复用现有 Nginx 的 `/evtol` 路径前缀** 方式，
部署到已经在跑「农机通」（Node.js + systemd + Nginx + Cloudflare）的同一台服务器上。

## 0. 部署目标与共存约定

| 项 | 农机通（已有） | eVTOL（本次） |
|----|----------------|----------------|
| 监听 | `127.0.0.1:3000` | `127.0.0.1:8000` |
| 外部路径 | `https://你的域名/api/v1`、`/admin` | `https://你的域名/evtol/...` |
| 进程 | systemd `nongjitong.service` | systemd `evtol.service` |
| 数据库 | `/var/lib/nongjitong/nongjitong.sqlite` | `/var/lib/evtol/evtol.sqlite` |
| 反代/证书/Cloudflare | 已配 | **复用，不改动** |

要点：同域名、同 443、同证书，只在现有 Nginx server 块里**加一个 location**。
**不需要新增 DNS 记录、SSL 证书或改 Cloudflare 设置。** Cloudflare 的 SSL 模式沿用农机通
现在能正常工作的模式（通常 Full / Full strict）即可——别动它。

## 1. 安装运行时（Ubuntu 24.04 自带 Python 3.12）

```bash
sudo apt update
sudo apt install -y python3-venv python3-pip
python3 --version   # 期望 3.12.x
```

## 2. 放置代码并建虚拟环境

```bash
sudo mkdir -p /opt/evtol
# 把仓库的 server/ 目录放到 /opt/evtol/server（git clone 后复制，或 scp 上传）
# 结果应为 /opt/evtol/server/app/main.py 存在
sudo chown -R $USER:$USER /opt/evtol

python3 -m venv /opt/evtol/venv
/opt/evtol/venv/bin/pip install --upgrade pip
/opt/evtol/venv/bin/pip install -r /opt/evtol/server/requirements.txt
```

## 3. 配置环境文件

```bash
sudo cp /opt/evtol/server/deploy/evtol.env.example /etc/evtol.env
sudo chmod 600 /etc/evtol.env
# 生成随机密钥并填入强密码
openssl rand -hex 32      # 复制到 SECRET_KEY
sudo nano /etc/evtol.env  # 改 ADMIN_PASSWORD、SECRET_KEY；DB_PATH 保持默认
```

## 4. 安装 systemd 服务

```bash
sudo cp /opt/evtol/server/deploy/evtol.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now evtol
sudo systemctl status evtol            # 应为 active (running)
```
`StateDirectory=evtol` 会自动创建 `/var/lib/evtol` 并归属 `www-data`。

**本机自测（未过 Nginx，直连 8000；注意带 --root-path 时路由仍在根路径）：**
```bash
curl -s http://127.0.0.1:8000/health
curl -s http://127.0.0.1:8000/vertiports | head -c 200
curl -s http://127.0.0.1:8000/vertiports/vp001/occupancy
```
期望：`{"status":"ok"}`；12 个停机坪 JSON；vp001 占用含 ev002、`"occupied":true`。

## 5. 接入现有 Nginx

把 `deploy/nginx-evtol-location.conf` 里的 `location /evtol/ { ... }` 块，
加进**现有农机通的那个 `server { listen 443 ssl; server_name 你的域名; ... }`** 里，
与农机通的 location 并列。农机通的 Nginx 配置通常在
`/etc/nginx/sites-available/`（或 `conf.d/`）下，且其 `deploy/nginx-nongjitong-locations.conf`
是被 include 的片段——你也可以把 eVTOL 片段放成 `/etc/nginx/snippets/evtol.conf` 再在 server 块里 `include`。

```bash
sudo nginx -t          # 语法检查
sudo systemctl reload nginx
```

**过反代验证（经 Cloudflare → Nginx → 8000）：**
```bash
curl -s https://你的域名/evtol/health
curl -s https://你的域名/evtol/vertiports | head -c 200
curl -s https://你的域名/evtol/vertiports/vp001/occupancy
# 浏览器打开 https://你的域名/evtol/docs 可看 Swagger（--root-path 已让文档在前缀下工作）
```

## 6. 备份（每个库各一份，与农机通分开）

```bash
# 例：每天 03:00 备份到 /var/backups/evtol
sudo mkdir -p /var/backups/evtol
( sudo crontab -l 2>/dev/null; echo '0 3 * * * sqlite3 /var/lib/evtol/evtol.sqlite ".backup /var/backups/evtol/evtol-$(date +\%F).sqlite"' ) | sudo crontab -
```

## 7. 更新发布

```bash
# 更新 /opt/evtol/server 代码后：
/opt/evtol/venv/bin/pip install -r /opt/evtol/server/requirements.txt
sudo systemctl restart evtol
sudo systemctl status evtol
```

## 8. 排错速查

- `systemctl status evtol` / `journalctl -u evtol -e` 看启动日志。
- 502：多半是 8000 没起或崩了——先看 `journalctl -u evtol`。
- 404 但 8000 直连正常：检查 Nginx `proxy_pass` 末尾斜杠（必须有，用于剥掉 `/evtol/` 前缀）。
- `/evtol/docs` 样式/接口 404：确认 `ExecStart` 带了 `--root-path /evtol`。
- `database is locked`：已内置 `busy_timeout=5000`；若仍频繁，确认只有一个 worker、且没有第二个进程在写同一文件。

## 9. 与 Android 的衔接（计划三）

部署完成后，Android 的 `SERVER_BASE_URL` 设为 `https://你的域名/evtol/`（注意末尾斜杠）。
公网走 HTTPS 后，可收紧 `network_security_config.xml`。这部分在计划三落地。

## 10. 现状边界

- 本手册部署的是**计划一**的后端（app 端 API + 占用 + 调度引擎）。
- **计划二（Web 管理台 + 鉴权）已于 2026-06-26 部署上线**：`https://bq-star.com/evtol/admin/login`，
  登录用 `/etc/evtol.env` 的 `ADMIN_USERNAME/ADMIN_PASSWORD`（lifespan 启动自动播种到 admin_users 表）。
  更新流程见本文件第 7 节（tar 覆盖 → venv pip install → systemctl restart）；本次新增依赖 bcrypt/itsdangerous。
  会话 Cookie 在生产为 Secure（`https_only`，经 Cloudflare 已验证登录流程）。无需改 Nginx/systemd。

## 11. 本次实际部署记录（bq-star.com · 2026-06-26）

已实际部署并通过公网验证。真实取值：

- 服务器：香港 Ubuntu 24.04.4，Python 3.12.3，登录用户 `dxd`（sudo 免密）。
- 域名：**bq-star.com**（Cloudflare 代理 + Let's Encrypt 源站证书，HSTS）。
- 代码目录：`/data/work/web/evtol`（属主 dxd，与农机通同级）；venv：`/data/work/web/evtol/venv`。
- 服务：`/etc/systemd/system/evtol.service`（User=www-data, UMask=0077, `--root-path /evtol`, 单 worker），监听 `127.0.0.1:8000`。
- 环境：`/etc/evtol.env`（root:www-data 640，含随机 SECRET_KEY 与 ADMIN_PASSWORD —— 用 `sudo cat /etc/evtol.env` 查看）。
- 数据库：`/var/lib/evtol/evtol.sqlite`（systemd StateDirectory，www-data 0600）。
- Nginx：片段 `/etc/nginx/snippets/evtol-locations.conf`，在 `sites-available/bq-star.conf` 的 `server_name bq-star.com` 443 块里 `include`（农机通 include 之后一行）；改前已备份 `bq-star.conf.<ts>.bak`。
- 对外地址：**https://bq-star.com/evtol/**（如 `/evtol/vertiports`、`/evtol/vertiports/{id}/occupancy`、`/evtol/docs`）。
- 验证：源站直连、经 Cloudflare、外网本机三处均通过；农机通 `/admin/`、公司站 `/` 回归正常。

更新发布：`tar` 覆盖 `/data/work/web/evtol` → `./venv/bin/pip install -r requirements.txt` → `sudo systemctl restart evtol`。
回滚 Nginx：删除 bq-star.conf 中的 evtol include 行（或还原 .bak）→ `sudo nginx -t && sudo systemctl reload nginx`。

> 安全提醒：本次通过临时 askpass 使用了私钥 passphrase，会话结束已删除本地临时文件；
> 该 passphrase 仍建议你后续轮换一次。
