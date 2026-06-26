# eVTOL Dispatch Server

为 Android eVTOL 叫车 App 提供的后端服务。基于 **FastAPI + SQLAlchemy + SQLite**，
内置一个**进程内模拟引擎**：以约 1 秒为步长推进飞行器位置、订单状态机与停机坪占用，
对外暴露与 Android DTO 对齐的只读/下单接口。

本目录是后端基础工程（计划一）的全部产物；Web 管理台、鉴权校验与公网部署属于后续计划。

---

## 1. 运行环境

- Python 3.12
- 依赖见 `requirements.txt`（FastAPI / uvicorn / SQLAlchemy / Pydantic 等）
- 数据库：SQLite 单文件（生产文件库走 WAL；测试用内存库）

## 2. 本地开发运行

```bash
cd server
python -m pip install -r requirements.txt
copy .env.example .env        # Windows；Linux/macOS: cp .env.example .env
python -m uvicorn app.main:app --reload --port 8000
```

启动后服务监听 `http://127.0.0.1:8000`。首次启动时：

1. `engine_lifespan` 初始化数据库引擎（按 `DB_PATH` 建库），
2. 若库为空则播种 **12 个停机坪 + 3 架飞行器**（见 `app/seed.py`），
3. 拉起后台模拟引擎协程，每秒推进一次世界状态。

快速健康检查：

```bash
curl http://127.0.0.1:8000/health          # {"status":"ok"}
curl http://127.0.0.1:8000/vertiports      # 返回 12 个停机坪
```

> 速查版命令见 `run_local.md`。

## 3. 运行测试

```bash
cd server
python -m pytest -v
```

预期 **35 个用例全部通过**，覆盖：geo 计算、DTO 契约、业务服务、各接口端到端、
模拟引擎单步与循环。

测试通过环境变量 `EVTOL_TESTING=1` 关闭 lifespan 的后台引擎循环，改由测试用例
手动调用 `tick(...)`，避免后台循环与测试 tick 同时写库；测试 fixture 使用独立的
内存 SQLite 库。该变量**仅供测试**，生产请勿设置。

## 4. Docker 运行

```bash
cd server
docker compose up --build
```

`docker-compose.yml` 将容器 `8000` 端口映射到宿主同名端口，并把命名卷
`evtol-data` 挂载到容器内 `/data`，SQLite 库持久化在 `/data/evtol.db`，
容器重建后数据不丢。`restart: unless-stopped` 保证异常退出自动拉起。

也可不使用 compose 直接构建运行（需自行挂卷以持久化）：

```bash
docker build -t evtol-server .
docker run -p 8000:8000 -v evtol-data:/data evtol-server
```

## 5. 单 worker 约束（务必遵守）

容器与 compose 均以 **`--workers 1`** 启动 uvicorn，**不可横向加 worker / 多进程**。
原因：

- **SQLite 单写者**：多进程并发写同一 SQLite 文件会触发锁竞争与
  `database is locked`；单 worker 串行写规避了这一点。
- **进程内模拟引擎**：模拟引擎是**单实例进程内协程**，维护飞行器位置、订单状态与
  占用关系，并由 `app.main` 的 lifespan 唯一驱动。多 worker 会出现多个引擎实例
  各自 tick、互相覆盖状态，导致数据错乱。

如需更高并发，应在后续计划中迁移到独立数据库（如 PostgreSQL）并将模拟引擎拆为
单独的常驻进程，而不是简单增加 uvicorn worker 数。

## 6. 环境变量（`.env`）

复制 `.env.example` 为 `.env` 后按需修改。变量表：

| 变量             | 默认值               | 说明                                                         |
|------------------|----------------------|--------------------------------------------------------------|
| `DB_PATH`        | `./evtol.db`         | SQLite 库文件路径。容器内固定为 `/data/evtol.db`（挂卷持久化）。 |
| `APP_API_KEY`    | 空                   | App 端访问密钥（预留，计划二接入鉴权后启用；当前为空即不校验）。 |
| `ADMIN_USERNAME` | `admin`              | 管理台用户名（预留给计划二的 Web 管理台登录）。               |
| `ADMIN_PASSWORD` | `change-me-please`   | 管理台密码（预留给计划二；**上线务必修改**）。               |
| `SECRET_KEY`     | `dev-secret`         | 会话/令牌签名密钥（预留给计划二；**上线务必替换为随机值**）。 |

> compose 通过 `${VAR:-default}` 读取宿主环境变量并带默认值，因此可用宿主 shell
> 环境或 `.env`（compose 默认读取当前目录 `.env`）覆盖这些值。

## 7. 接口清单

App 端接口（与设计文档 §6 一致，均无前缀）：

| 方法 & 路径                              | 说明                                                   |
|------------------------------------------|--------------------------------------------------------|
| `GET  /health`                           | 健康检查，返回 `{"status":"ok"}`。                     |
| `GET  /vertiports`                       | 全部停机坪列表（12 个）。                              |
| `GET  /vehicles`                         | 全部飞行器列表（3 架）。                              |
| `POST /vehicles/nearby`                  | 按经纬度返回最近的 3 架飞行器。                        |
| `GET  /vertiports/{id}/occupancy`        | 某停机坪占用情况（`occupied` + 停靠飞行器列表）。      |
| `POST /price/estimate`                   | 按上车点 + 目的地停机坪估价（金额/距离/取消费）。      |
| `POST /orders`                           | 下单：分配最近可用飞行器并创建 `RESERVED` 订单。       |
| `GET  /orders`                           | 订单列表（按创建时间倒序）。                          |
| `GET  /orders/{id}`                      | 单个订单详情。                                        |
| `POST /orders/{id}/board`                | 登机：`BOARDING` → `IN_FLIGHT`。                       |
| `POST /orders/{id}/cancel`               | 取消：按行程进度计算取消费，订单转 `RETURNING`。       |

请求/响应字段定义见 `app/schemas.py`（与 Android DTO 对齐，含
`pickupVertiport` / `vehicleOrigin` / `cancellationFeeCents` 等）。

> 后续计划：Web 管理台、`/admin/*` 鉴权守卫与公网 HTTPS 部署（计划二）；
> Android 客户端接入与离线缓存（计划三）。
