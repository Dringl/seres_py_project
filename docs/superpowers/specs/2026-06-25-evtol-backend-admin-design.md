# eVTOL 调度系统 · 后端服务 + Web 管理台 设计文档

- 日期：2026-06-25
- 状态：已通过设计评审，待写实现计划
- 适用工程：`seres_py_project`（Android eVTOL 调度演示 app，包名 `com.seres.evtoldemo`）

## 1. 背景与问题

现有 Android 工程本身就按"有一个后端"设计：已存在 Retrofit 接口
`com.seres.evtoldemo.data.remote.DispatchApi` 与配套 DTO，`BuildConfig.BASE_URL`
默认 `http://10.0.2.2:8000/`（模拟器访问宿主机的 8000 端口），工程目录名
`seres_py_project` 也暗示原计划用 Python 后端。但依赖注入实际绑定的是
`FakeDispatchRepository`——纯内存假数据 + 本地 Room 仅存订单历史。

结果：**每台设备各自模拟、数据互不相通**。所有"飞行器会移动、电量会掉、订单状态
流转"的逻辑都写在每台手机的 `FakeDispatchRepository` 里。

### 目标
1. 配置真实后端服务 + 服务器数据库，让不同设备看到**同一份**飞行器信息。
2. 提供 Web 后台管理台（监控 + 管理）。
3. 支持查询"某停机坪当前是否有飞行器"。

### 核心设计原则
把散落在各手机端的调度/生命周期模拟**上收到服务器**，使**服务器成为唯一数据源
（single source of truth）**；app 与 Web 都只读写它。

## 2. 已确认的技术决策

| 维度 | 决策 |
|------|------|
| 部署场景 | 公网云服务器 |
| 数据库 | SQLite（预留迁移到 PostgreSQL 的路径） |
| Web 范围 | 监控 + 管理（含 CRUD / 调度 / 改状态） |
| 后端技术栈 | Python + FastAPI |
| 实时机制 | REST 轮询打底（app 端降频 + 客户端插值） |
| 鉴权范围 | 仅管理端鉴权；app 接口开放（可选固定 App Key 弱校验） |

## 3. 总体架构

```
 多台 Android 设备 ─┐                ┌─ 云服务器 · FastAPI（单 Uvicorn worker）──┐
                    ├── HTTP/JSON ──▶│  REST API + 鉴权                          │
 Web 管理台(浏览器) ─┘   :8000        │  调度模拟引擎（asyncio 后台 tick）        │──读写──▶ SQLite
                                     │  状态机 · 唯一数据源                      │
                                     └────────────────────────────────────────┘
 公网入口：Caddy 反向代理 + 自动 HTTPS → 127.0.0.1:8000
```

Android 端从 `FakeDispatchRepository` 切换到新的 `RemoteDispatchRepository`
（Fake 保留作为离线/调试开关）。

## 4. 后端工程结构（新增 `server/` 目录，不改动现有 Android 源码树结构）

```
server/
  app/
    main.py            # FastAPI 入口；挂载 API + 管理 Web + 静态资源；启动模拟引擎
    config.py          # 环境配置（管理员账号密码、App Key、DB 路径、SECRET_KEY）
    database.py        # SQLAlchemy 引擎/会话；SQLite 开 WAL，check_same_thread=False
    models.py          # ORM：Vertiport / Vehicle / Order / AdminUser(可选)
    schemas.py         # Pydantic DTO —— 字段与 Android DTO 严格对齐
    auth.py            # 管理员登录 + 令牌（HttpOnly Cookie Session 或 Bearer）
    seed.py            # 初始化 12 个停机坪 + 3 架飞行器（沿用现有 Fake 数据）
    simulation.py      # 服务端调度/生命周期引擎（移植 FakeDispatchRepository）
    geo.py             # 经纬度 haversine 距离 / 插值（与 Android GeoPoint.distanceTo 对齐）
    routers/
      mobile.py        # 面向 app 的接口（DispatchApi 契约 + 新增）
      admin.py         # 管理接口（CRUD / 调度 / 改状态）
    web/
      templates/       # Jinja2 管理页（登录、总览、机队、停机坪、订单）
      static/          # css / js（高德地图 + 2 秒轮询刷新）
  tests/               # pytest + httpx 契约与引擎测试
  requirements.txt
  Dockerfile
  docker-compose.yml
  Caddyfile            # 反向代理 + 自动 HTTPS
  .env.example
  README.md
```

技术栈：FastAPI + Uvicorn、SQLAlchemy 2.0、Pydantic v2、Jinja2、passlib[bcrypt]
（密码哈希）、pytest + httpx（测试）。地图用高德 JS API（`gradle.properties` 已有
`GAODE_WEB_KEY`，与 app 一致）；零密钥备选 Leaflet + OSM。

## 5. 数据库模型（SQLite，镜像 Android 领域模型）

```
vertiports(
  id            TEXT PK,      -- 如 vp001
  name          TEXT,
  latitude      REAL,
  longitude     REAL
)

vehicles(
  id                  TEXT PK,    -- 如 ev001
  name                TEXT,
  latitude            REAL,
  longitude           REAL,
  battery_percent     INTEGER,
  online              INTEGER,    -- 0/1
  status              TEXT,       -- VehicleStatus 枚举名
  current_vertiport_id TEXT NULL FK→vertiports.id,  -- 停靠时写入，支撑占用查询
  updated_at          INTEGER     -- epoch millis
)

orders(
  id                   TEXT PK,    -- 如 ODR-xxxxxx-nnn
  pickup_lat           REAL,
  pickup_lng           REAL,
  pickup_vertiport_id  TEXT FK→vertiports.id,
  vehicle_origin_lat   REAL,
  vehicle_origin_lng   REAL,
  destination_id       TEXT FK→vertiports.id,
  vehicle_id           TEXT FK→vehicles.id,
  status               TEXT,       -- OrderStatus 枚举名
  amount_cents         INTEGER,
  distance_km          REAL,
  currency             TEXT,       -- 默认 CNY
  cancellation_fee_cents INTEGER,
  created_at           INTEGER,
  updated_at           INTEGER
)

admin_users(            -- 可选；若只用单管理员，则从 .env 读取，不建表
  username       TEXT PK,
  password_hash  TEXT
)
```

约定：
- 枚举 `VehicleStatus`（IDLE/RESERVED/BOARDING/IN_FLIGHT/CHARGING/MAINTENANCE/OFFLINE）
  与 `OrderStatus`（CREATED/ASSIGNED/RESERVED/BOARDING/IN_FLIGHT/RETURNING/DONE/CANCELED/FAILED）
  **存字符串，名称与 Kotlin 枚举完全一致**，DTO 映射零成本。
- `vehicles.current_vertiport_id`：飞行器停靠某坪时由引擎写入，使"停机坪是否有
  飞行器"成为一次普通字段查询，无需每次实时算距离。

## 6. 面向 app 的接口契约

### 6.1 保留（JSON 形状与现有 DispatchApi/DTO 一致，最小化 Android 改动）

```
GET  /vertiports                 → [VertiportDto]
POST /vehicles/nearby            {latitude, longitude, radiusKm}            → [EvtolDto]
POST /price/estimate             {pickup:{lat,lng}, destinationVertiportId} → PriceEstimateDto
POST /orders                     {pickup, destinationVertiportId,
                                  pickupVertiportId}                        → OrderDto
POST /orders/{id}/cancel         → 204 / OrderDto
```

变更点：`POST /orders` 请求体**新增 `pickupVertiportId`**（现有网络 DTO 丢失了
该字段，会导致上车点不一致）。Android `CreateOrderRequestDto` 同步加该字段。

### 6.2 新增（同步加入 Android `DispatchApi`）

```
GET  /orders/{id}                → OrderDto            （observeOrder 改轮询此接口）
POST /orders/{id}/board          → 204 / OrderDto      （原 confirmPassengerBoarded）
GET  /orders                     → [OrderDto]          （服务端共享订单历史）
GET  /vertiports/{id}/occupancy  → OccupancyDto        ★核心新需求
GET  /vehicles                   → [EvtolDto]          （全量机队，Web/调试用）
```

`OccupancyDto`：
```json
{
  "vertiportId": "vp001",
  "name": "江北嘴商务区停机坪",
  "occupied": true,
  "vehicles": [ EvtolDto, ... ]
}
```

### 6.3 DTO 字段（与现有 Android DTO 对齐）

- `GeoPointDto{latitude, longitude}`
- `VertiportDto{id, name, location:GeoPointDto}`
- `EvtolDto{id, name, location, batteryPercent, online, status}`
- `PriceEstimateDto{amountCents, distanceKm, currency}`
- `OrderDto{id, vehicleId, status, price:PriceEstimateDto, createdAt, updatedAt,
   pickup:GeoPointDto, destination:VertiportDto}`

> 注：`OrderDto` 当前未携带 `pickupVertiport` / `cancellationFee`。是否补充见
> §14 待定项；默认先保持现状以最小化 Android 改动。

### 6.4 错误码
- 无可用车 → 409（Android 映射为 `NoAvailableVehicleException`）
- 订单不存在 → 404
- 非法状态流转（如对不可取消订单取消）→ 409
- 鉴权失败 → 401

## 7. 管理端 API + Web 页面（均需登录）

### 7.1 管理 API
```
POST   /admin/login                         {username, password}      → 令牌
POST   /admin/logout
GET    /admin/api/overview                  → 机队/订单概览统计
GET    /admin/api/vehicles                  → [车辆]
POST   /admin/api/vehicles                  → 新增
PUT    /admin/api/vehicles/{id}             → 改属性/状态/位置
DELETE /admin/api/vehicles/{id}
GET/POST/PUT/DELETE /admin/api/vertiports   → 停机坪管理
GET    /admin/api/orders                    → 全部订单
POST   /admin/api/orders/{id}/cancel        → 强制取消
POST   /admin/api/orders/{id}/status        → 强制改状态（运营干预）
```

### 7.2 Web 页面（Jinja2 + 原生 JS + 高德地图，2 秒轮询）
- `/admin/login`：登录页
- `/admin`：**总览大屏**——地图叠加飞行器+停机坪、机队状态表、**停机坪占用面板**
  （直接看到每个坪当前有无飞行器、是哪几架）
- `/admin/vehicles`：机队管理（增删改状态/位置）
- `/admin/vertiports`：停机坪管理
- `/admin/orders`：订单列表与干预

"查停机坪是否有飞行器"在总览页和停机坪页都可直接查看。

## 8. 服务器调度模拟引擎（移植 Fake 的生命周期）

FastAPI 启动时拉起**一个 asyncio 后台 tick 循环**（约 1 秒/拍），统一推进所有进行中
订单与飞行器：

- `RESERVED`：飞行器飞向上车坪 → 到达转 `BOARDING`
- `BOARDING`：等待 `POST /orders/{id}/board` → 转 `IN_FLIGHT`
- `IN_FLIGHT`：飞向目的地 → 到达转 `DONE`，飞行器在目的坪转 `IDLE` 停靠
- 取消：转 `RETURNING` → 飞回最近坪 → 转 `CANCELED`
- 每拍把位置/电量/状态写回 SQLite

移动参数沿用 Fake：上车段 ~95km/h、飞行段 ~180km/h、返航 ~110km/h；电量按行程
递减。区别在于服务端用"中央 tick 循环读库 → 推进 → 写库"，便于持久化与重启恢复，
不再为每个订单开独立协程定时器。

**效果：即便没有任何 app 连接，飞行器也在服务器上持续"活着"，多设备共享同一状态。**

占用判定：飞行器 `current_vertiport_id` 非空（或 status ∈ {IDLE, CHARGING,
BOARDING} 且距某坪 < 80m）即视为"在坪"。引擎在停靠/起飞时维护该字段。

## 9. Android 端改造

- 新增 `RemoteDispatchRepository : DispatchRepository`，用扩展后的 `DispatchApi`
  实现全部方法；`AppModule` 改绑它（Fake 保留，用 `BuildConfig` 开关切换离线模式）。
- `observeOrder(id)`：返回一个**每 ~1.5s 轮询 `GET /orders/{id}`** 的 Flow，直到
  终态（DONE/CANCELED/FAILED）后停止。
- 进行中机队轮询从 **160ms 降到 ~1s**（`MainViewModel.updateVehiclePollingByOrderState`
  的 `delay(160)`），并在 UI 层对飞行器位置做**两点间插值**保持平滑，弥补服务端
  ~1s 采样间隔。
- `getOrderHistory()` 改读 `GET /orders`；Room 降级为可选离线缓存。
- `BASE_URL` 配置化：在 `app/build.gradle.kts` 从 `gradle.properties` 读
  `SERVER_BASE_URL`，构建期注入 `BuildConfig.BASE_URL`，指向云服务器域名。
- 公网上线后收紧 `network_security_config.xml`（仅允许你的域名，或强制 TLS）。

## 10. 鉴权与安全（仅管理端）

- 管理 Web：账号 + 密码登录，签发令牌（HttpOnly Cookie Session 或 Bearer）；
  所有 `/admin/*` 校验；密码用 bcrypt（passlib）哈希存储。
- app 接口开放；可选带固定 `X-App-Key` 头做弱校验防脚本乱刷（写入 BuildConfig）。
- 公网建议 HTTPS：Caddy 自动签发证书；Uvicorn 仅绑 `127.0.0.1`。
- 环境变量：`ADMIN_USERNAME / ADMIN_PASSWORD / APP_API_KEY / SECRET_KEY / DB_PATH`。

## 11. 部署（公网云）

- `Dockerfile`：`python:3.12-slim` + uvicorn。
- `docker-compose.yml`：挂载 SQLite 数据卷持久化；可带 Caddy 服务。
- `Caddyfile`：`https://你的域名 → 127.0.0.1:8000`，自动 HTTPS。
- ⚠️ **关键约束：必须单 Uvicorn worker**。SQLite + 进程内模拟引擎，多 worker
  会各跑一份引擎并抢写库。并发不足时再迁移到 PostgreSQL + 多 worker（引擎独立化）。
- 防火墙仅放行 80/443。
- README 提供一键部署步骤。

## 12. 测试策略

- 后端（pytest + httpx）：
  - 接口契约：vertiports / nearby / price / 下单全生命周期 / occupancy / 鉴权
  - 引擎单测：移动插值、状态流转、占用字段维护
  - **契约测试**：断言 JSON 形状与 Android DTO 字段一致
- Android：`RemoteDispatchRepository` 用 MockWebServer 做单测（可选）。

## 13. 实施阶段拆分

1. **后端骨架**：FastAPI + SQLite + 模型 + seed + 与 Fake 等价的 app 接口 → app 可指过来
2. **服务端模拟引擎**：共享移动机队
3. **占用接口 + Web 监控大屏** → 满足"查停机坪是否有飞行器"
4. **管理端 CRUD/调度 + 鉴权**
5. **Android 切换**：RemoteRepository、新增接口、配置化 BASE_URL、轮询降频+插值
6. **Docker + Caddy/HTTPS + 部署文档**

## 14. 待定项（不阻塞实现，实现时确认默认值）

- 地图库：高德 JS（默认，已有 web key）vs Leaflet+OSM（零密钥）。
- 订单历史：是否保留本地 Room 作为离线缓存（默认保留）。
- `OrderDto` 是否补 `pickupVertiport` / `cancellationFee` 字段（默认不补，最小改动）。
- 管理台形态：多页 Jinja2（默认）vs 单页应用。
- 是否启用 `admin_users` 表（默认单管理员走 .env）。

## 15. 非目标（YAGNI）

- 不做 app 端用户/设备账号体系（鉴权仅管理端）。
- 不做支付、真实计费结算。
- 不引入消息队列、微服务拆分。
- 暂不做 WebSocket/SSE（轮询打底，后续可平滑增量）。
