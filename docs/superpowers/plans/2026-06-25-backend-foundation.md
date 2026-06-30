# 计划一 · 后端基础与机队引擎 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 `server/` 下用 FastAPI + SQLite 搭出一个能跑、能测、机队会自主移动的后端，完整实现 app 端接口（含停机坪占用查询），作为多设备共享的唯一数据源。

**Architecture:** 单 Uvicorn 进程。SQLAlchemy 2.0 + SQLite（WAL）做持久化；Pydantic v2 schema 的 JSON 字段名与 Android Moshi DTO 严格对齐（camelCase）；一个 asyncio 后台 tick 循环（约 1 秒/拍）读库→推进所有进行中订单与飞行器→写库，移植自 `FakeDispatchRepository` 的生命周期与移动参数。

**Tech Stack:** Python 3.12（部署）/3.13（本机）、FastAPI、Uvicorn、SQLAlchemy 2.0、Pydantic v2、pytest + httpx。

## Global Constraints

- 所有面向 app 的 JSON 字段名必须为 camelCase，且与现有 Android DTO 完全一致：
  `latitude, longitude, batteryPercent, online, status, amountCents, distanceKm,
  currency, cancellationFeeCents, radiusKm, pickup, location, destinationVertiportId,
  pickupVertiportId, vehicleId, price, createdAt, updatedAt, destination,
  pickupVertiport, vehicleOrigin, id, name, vertiportId, occupied, vehicles`。
- 枚举值用字符串，名称与 Kotlin 枚举一致：
  `VehicleStatus = IDLE|RESERVED|BOARDING|IN_FLIGHT|CHARGING|MAINTENANCE|OFFLINE`；
  `OrderStatus = CREATED|ASSIGNED|RESERVED|BOARDING|IN_FLIGHT|RETURNING|DONE|CANCELED|FAILED`。
- 时间戳用 epoch 毫秒（`int`），与 Android `System.currentTimeMillis()` 对齐。
- 距离计算用 haversine，地球半径 `6371.0` km，与 `GeoPoint.distanceTo` 一致。
- 计费公式与 Fake 一致：`distanceKm = max(haversine, 1.0)`；
  `amountCents = 2800 + int(distanceKm * 760)`；`currency = "CNY"`。
- 部署必须**单 worker**（SQLite + 进程内引擎）。
- 所有路径相对仓库根；后端代码全部在 `server/` 下，不改动 `app/`（Android）目录。

---

### Task 1: 工程骨架 + 配置 + 健康检查

**Files:**
- Create: `server/requirements.txt`
- Create: `server/.env.example`
- Create: `server/app/__init__.py`
- Create: `server/app/config.py`
- Create: `server/app/main.py`
- Create: `server/tests/__init__.py`
- Create: `server/tests/conftest.py`
- Create: `server/tests/test_health.py`

**Interfaces:**
- Produces: `app.config.Settings`（字段 `db_path:str`、`app_api_key:str|None`）与单例
  `get_settings() -> Settings`；`app.main.app`（FastAPI 实例，含 `GET /health`）。

- [ ] **Step 1: 写依赖与配置文件**

`server/requirements.txt`:
```
fastapi>=0.115
uvicorn[standard]>=0.30
sqlalchemy>=2.0
pydantic>=2.7
pydantic-settings>=2.3
jinja2>=3.1
python-multipart>=0.0.9
httpx>=0.27
pytest>=8.0
pytest-asyncio>=0.23
```

`server/.env.example`:
```
DB_PATH=./evtol.db
APP_API_KEY=
ADMIN_USERNAME=admin
ADMIN_PASSWORD=change-me-please
SECRET_KEY=replace-with-random-hex
```

- [ ] **Step 2: 写配置模块**

`server/app/config.py`:
```python
from functools import lru_cache
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    db_path: str = "./evtol.db"
    app_api_key: str | None = None
    admin_username: str = "admin"
    admin_password: str = "change-me-please"
    secret_key: str = "dev-secret"


@lru_cache
def get_settings() -> Settings:
    return Settings()
```

- [ ] **Step 3: 写 FastAPI 入口与健康检查**

`server/app/main.py`:
```python
from fastapi import FastAPI

app = FastAPI(title="eVTOL Dispatch Server")


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}
```

`server/app/__init__.py`、`server/tests/__init__.py`：空文件。

- [ ] **Step 4: 写测试夹具与失败测试**

`server/tests/conftest.py`:
```python
import os
import sys

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))

import pytest
from fastapi.testclient import TestClient

from app.main import app


@pytest.fixture
def client() -> TestClient:
    return TestClient(app)
```

`server/tests/test_health.py`:
```python
def test_health_ok(client):
    resp = client.get("/health")
    assert resp.status_code == 200
    assert resp.json() == {"status": "ok"}
```

- [ ] **Step 5: 装依赖并跑测试**

Run（在 `server/` 目录）:
```bash
cd server && python -m pip install -r requirements.txt && python -m pytest tests/test_health.py -v
```
Expected: `test_health_ok PASSED`。

- [ ] **Step 6: 提交**

```bash
git add server/
git commit -m "feat(server): 工程骨架与健康检查端点"
```

---

### Task 2: 经纬度工具（距离 / 插值）

**Files:**
- Create: `server/app/geo.py`
- Create: `server/tests/test_geo.py`

**Interfaces:**
- Produces:
  `haversine_km(lat1:float, lng1:float, lat2:float, lng2:float) -> float`；
  `interpolate(lat1, lng1, lat2, lng2, ratio:float) -> tuple[float,float]`；
  `step_toward(lat, lng, tlat, tlng, max_km:float) -> tuple[float,float,bool]`
  （返回新坐标与 `arrived` 标志：当剩余距离 ≤ max_km 时直接吸附到目标并置 True）。

- [ ] **Step 1: 写失败测试**

`server/tests/test_geo.py`:
```python
import math

from app.geo import haversine_km, interpolate, step_toward


def test_haversine_zero():
    assert haversine_km(29.5, 106.5, 29.5, 106.5) == 0.0


def test_haversine_known_distance():
    # 江北嘴(29.5792,106.5758) → 解放碑(29.5582,106.5755) 约 2.3km
    d = haversine_km(29.5792, 106.5758, 29.5582, 106.5755)
    assert 2.0 < d < 2.7


def test_interpolate_midpoint():
    lat, lng = interpolate(0.0, 0.0, 2.0, 4.0, 0.5)
    assert math.isclose(lat, 1.0)
    assert math.isclose(lng, 2.0)


def test_step_toward_arrives_when_close():
    lat, lng, arrived = step_toward(29.50, 106.50, 29.5001, 106.5001, max_km=1.0)
    assert arrived is True
    assert math.isclose(lat, 29.5001)
    assert math.isclose(lng, 106.5001)


def test_step_toward_partial_move():
    lat, lng, arrived = step_toward(29.50, 106.50, 30.50, 106.50, max_km=1.0)
    assert arrived is False
    assert 29.50 < lat < 30.50
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd server && python -m pytest tests/test_geo.py -v`
Expected: FAIL（`ModuleNotFoundError: app.geo`）。

- [ ] **Step 3: 实现 geo 模块**

`server/app/geo.py`:
```python
import math

EARTH_RADIUS_KM = 6371.0


def haversine_km(lat1: float, lng1: float, lat2: float, lng2: float) -> float:
    d_lat = math.radians(lat2 - lat1)
    d_lng = math.radians(lng2 - lng1)
    a = (
        math.sin(d_lat / 2) ** 2
        + math.sin(d_lng / 2) ** 2 * math.cos(math.radians(lat1)) * math.cos(math.radians(lat2))
    )
    c = 2 * math.asin(math.sqrt(min(max(a, 0.0), 1.0)))
    return EARTH_RADIUS_KM * c


def interpolate(lat1: float, lng1: float, lat2: float, lng2: float, ratio: float) -> tuple[float, float]:
    r = min(max(ratio, 0.0), 1.0)
    return (lat1 + (lat2 - lat1) * r, lng1 + (lng2 - lng1) * r)


def step_toward(lat: float, lng: float, tlat: float, tlng: float, max_km: float) -> tuple[float, float, bool]:
    remaining = haversine_km(lat, lng, tlat, tlng)
    if remaining <= max_km or remaining == 0.0:
        return (tlat, tlng, True)
    ratio = max_km / remaining
    new_lat, new_lng = interpolate(lat, lng, tlat, tlng, ratio)
    return (new_lat, new_lng, False)
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd server && python -m pytest tests/test_geo.py -v`
Expected: 5 passed。

- [ ] **Step 5: 提交**

```bash
git add server/app/geo.py server/tests/test_geo.py
git commit -m "feat(server): 经纬度距离与移动插值工具"
```

---

### Task 3: 数据库与 ORM 模型

**Files:**
- Create: `server/app/database.py`
- Create: `server/app/models.py`
- Create: `server/tests/test_models.py`

**Interfaces:**
- Produces：`app.database.Base`（DeclarativeBase）、`app.database.make_engine(db_path)`、
  `app.database.SessionLocal`（可重绑定的 sessionmaker）、`app.database.init_app_engine(db_path)`、
  `app.database.get_session()`（FastAPI 依赖，yield Session）。
- Produces ORM：`Vertiport(id,name,latitude,longitude)`、
  `Vehicle(id,name,latitude,longitude,battery_percent,online,status,current_vertiport_id,updated_at)`、
  `Order(id,pickup_lat,pickup_lng,pickup_vertiport_id,vehicle_origin_lat,vehicle_origin_lng,
  destination_id,vehicle_id,status,amount_cents,distance_km,currency,cancellation_fee_cents,
  created_at,updated_at)`、`AdminUser(username,password_hash,created_at)`。

- [ ] **Step 1: 写失败测试**

`server/tests/test_models.py`:
```python
from sqlalchemy import select

from app.database import Base, make_engine, new_session
from app.models import Vehicle, Vertiport


def test_can_create_and_query_vehicle():
    engine = make_engine("sqlite://")  # in-memory
    Base.metadata.create_all(engine)
    with new_session(engine) as session:
        session.add(Vertiport(id="vp001", name="测试坪", latitude=29.5, longitude=106.5))
        session.add(
            Vehicle(
                id="ev001",
                name="Eagle-01",
                latitude=29.5,
                longitude=106.5,
                battery_percent=88,
                online=True,
                status="IDLE",
                current_vertiport_id="vp001",
                updated_at=1000,
            )
        )
        session.commit()
        rows = session.execute(select(Vehicle)).scalars().all()
        assert len(rows) == 1
        assert rows[0].status == "IDLE"
        assert rows[0].current_vertiport_id == "vp001"
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd server && python -m pytest tests/test_models.py -v`
Expected: FAIL（`ModuleNotFoundError: app.database`）。

- [ ] **Step 3: 实现 database.py**

`server/app/database.py`:
```python
from contextlib import contextmanager
from collections.abc import Iterator

from sqlalchemy import create_engine, event
from sqlalchemy.engine import Engine
from sqlalchemy.orm import DeclarativeBase, Session, sessionmaker


class Base(DeclarativeBase):
    pass


def make_engine(db_path: str) -> Engine:
    if db_path == "sqlite://" or db_path.startswith("sqlite://"):
        url = db_path
    else:
        url = f"sqlite:///{db_path}"
    engine = create_engine(url, connect_args={"check_same_thread": False})

    @event.listens_for(engine, "connect")
    def _set_sqlite_pragma(dbapi_conn, _rec):  # noqa: ANN001
        cur = dbapi_conn.cursor()
        cur.execute("PRAGMA journal_mode=WAL")
        cur.execute("PRAGMA foreign_keys=ON")
        cur.close()

    return engine


def new_session(engine: Engine) -> Session:
    return Session(engine)


# 运行期单例：在 app 启动时由 init_app_engine 绑定
SessionLocal: sessionmaker | None = None
_engine: Engine | None = None


def init_app_engine(db_path: str) -> Engine:
    global SessionLocal, _engine
    _engine = make_engine(db_path)
    Base.metadata.create_all(_engine)
    SessionLocal = sessionmaker(bind=_engine, expire_on_commit=False)
    return _engine


def get_engine() -> Engine:
    assert _engine is not None, "engine 未初始化，请先调用 init_app_engine"
    return _engine


def get_session() -> Iterator[Session]:
    assert SessionLocal is not None, "SessionLocal 未初始化"
    session = SessionLocal()
    try:
        yield session
    finally:
        session.close()


@contextmanager
def session_scope() -> Iterator[Session]:
    assert SessionLocal is not None, "SessionLocal 未初始化"
    session = SessionLocal()
    try:
        yield session
        session.commit()
    except Exception:
        session.rollback()
        raise
    finally:
        session.close()
```

- [ ] **Step 4: 实现 models.py**

`server/app/models.py`:
```python
from sqlalchemy import Boolean, Float, ForeignKey, Integer, String
from sqlalchemy.orm import Mapped, mapped_column

from app.database import Base


class Vertiport(Base):
    __tablename__ = "vertiports"
    id: Mapped[str] = mapped_column(String, primary_key=True)
    name: Mapped[str] = mapped_column(String)
    latitude: Mapped[float] = mapped_column(Float)
    longitude: Mapped[float] = mapped_column(Float)


class Vehicle(Base):
    __tablename__ = "vehicles"
    id: Mapped[str] = mapped_column(String, primary_key=True)
    name: Mapped[str] = mapped_column(String)
    latitude: Mapped[float] = mapped_column(Float)
    longitude: Mapped[float] = mapped_column(Float)
    battery_percent: Mapped[int] = mapped_column(Integer)
    online: Mapped[bool] = mapped_column(Boolean, default=True)
    status: Mapped[str] = mapped_column(String, default="IDLE")
    current_vertiport_id: Mapped[str | None] = mapped_column(
        ForeignKey("vertiports.id"), nullable=True
    )
    updated_at: Mapped[int] = mapped_column(Integer, default=0)


class Order(Base):
    __tablename__ = "orders"
    id: Mapped[str] = mapped_column(String, primary_key=True)
    pickup_lat: Mapped[float] = mapped_column(Float)
    pickup_lng: Mapped[float] = mapped_column(Float)
    pickup_vertiport_id: Mapped[str] = mapped_column(ForeignKey("vertiports.id"))
    vehicle_origin_lat: Mapped[float] = mapped_column(Float)
    vehicle_origin_lng: Mapped[float] = mapped_column(Float)
    destination_id: Mapped[str] = mapped_column(ForeignKey("vertiports.id"))
    vehicle_id: Mapped[str] = mapped_column(ForeignKey("vehicles.id"))
    status: Mapped[str] = mapped_column(String)
    amount_cents: Mapped[int] = mapped_column(Integer)
    distance_km: Mapped[float] = mapped_column(Float)
    currency: Mapped[str] = mapped_column(String, default="CNY")
    cancellation_fee_cents: Mapped[int] = mapped_column(Integer, default=0)
    created_at: Mapped[int] = mapped_column(Integer)
    updated_at: Mapped[int] = mapped_column(Integer)


class AdminUser(Base):
    __tablename__ = "admin_users"
    username: Mapped[str] = mapped_column(String, primary_key=True)
    password_hash: Mapped[str] = mapped_column(String)
    created_at: Mapped[int] = mapped_column(Integer, default=0)
```

- [ ] **Step 5: 跑测试确认通过**

Run: `cd server && python -m pytest tests/test_models.py -v`
Expected: 1 passed。

- [ ] **Step 6: 提交**

```bash
git add server/app/database.py server/app/models.py server/tests/test_models.py
git commit -m "feat(server): SQLite 引擎(WAL) 与 ORM 模型"
```

---

### Task 4: 种子数据

**Files:**
- Create: `server/app/seed.py`
- Create: `server/tests/test_seed.py`

**Interfaces:**
- Consumes: `app.models`、`app.database`。
- Produces: `app.seed.seed_if_empty(session) -> None`（仅当 vertiports 为空时插入
  12 个停机坪 + 3 架飞行器；飞行器初始 `current_vertiport_id` 设为其所在坪）。
- Produces: 常量 `app.seed.VERTIPORTS`（list[dict]）、`app.seed.VEHICLES`（list[dict]）。

- [ ] **Step 1: 写失败测试**

`server/tests/test_seed.py`:
```python
from sqlalchemy import select

from app.database import Base, make_engine, new_session
from app.models import Vehicle, Vertiport
from app.seed import seed_if_empty


def test_seed_populates_once():
    engine = make_engine("sqlite://")
    Base.metadata.create_all(engine)
    with new_session(engine) as session:
        seed_if_empty(session)
        session.commit()
        assert len(session.execute(select(Vertiport)).scalars().all()) == 12
        vehicles = session.execute(select(Vehicle)).scalars().all()
        assert len(vehicles) == 3
        # 飞行器初始停靠在某个停机坪
        assert all(v.current_vertiport_id is not None for v in vehicles)
        # 再次调用不重复插入
        seed_if_empty(session)
        session.commit()
        assert len(session.execute(select(Vertiport)).scalars().all()) == 12
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd server && python -m pytest tests/test_seed.py -v`
Expected: FAIL（`ModuleNotFoundError: app.seed`）。

- [ ] **Step 3: 实现 seed.py**

`server/app/seed.py`（数据取自 `FakeDispatchRepository`）:
```python
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.models import Vehicle, Vertiport

VERTIPORTS = [
    {"id": "vp001", "name": "江北嘴商务区停机坪", "latitude": 29.5792, "longitude": 106.5758},
    {"id": "vp002", "name": "解放碑停机坪", "latitude": 29.5582, "longitude": 106.5755},
    {"id": "vp003", "name": "观音桥停机坪", "latitude": 29.5845, "longitude": 106.5332},
    {"id": "vp004", "name": "南滨路停机坪", "latitude": 29.5468, "longitude": 106.5851},
    {"id": "vp005", "name": "重庆北站停机坪", "latitude": 29.6142, "longitude": 106.5511},
    {"id": "vp006", "name": "两江机场停机坪", "latitude": 29.7211, "longitude": 106.6439},
    {"id": "vp007", "name": "重庆西站停机坪", "latitude": 29.5036, "longitude": 106.4301},
    {"id": "vp008", "name": "大学城停机坪", "latitude": 29.5976, "longitude": 106.2998},
    {"id": "vp009", "name": "照母山停机坪", "latitude": 29.6318, "longitude": 106.5357},
    {"id": "vp010", "name": "巴南龙洲湾停机坪", "latitude": 29.4042, "longitude": 106.5414},
    {"id": "vp011", "name": "磁器口停机坪", "latitude": 29.5829, "longitude": 106.4498},
    {"id": "vp012", "name": "南岸茶园停机坪", "latitude": 29.4947, "longitude": 106.6378},
]

# location 与 vp003 / vp001 / vp006 对应，初始停靠在该坪
VEHICLES = [
    {"id": "ev001", "name": "Eagle-01", "latitude": 29.5845, "longitude": 106.5332,
     "battery_percent": 88, "current_vertiport_id": "vp003"},
    {"id": "ev002", "name": "Eagle-02", "latitude": 29.5792, "longitude": 106.5758,
     "battery_percent": 74, "current_vertiport_id": "vp001"},
    {"id": "ev003", "name": "Eagle-03", "latitude": 29.7211, "longitude": 106.6439,
     "battery_percent": 81, "current_vertiport_id": "vp006"},
]


def seed_if_empty(session: Session) -> None:
    if session.execute(select(Vertiport)).first() is not None:
        return
    for vp in VERTIPORTS:
        session.add(Vertiport(**vp))
    for v in VEHICLES:
        session.add(
            Vehicle(
                id=v["id"],
                name=v["name"],
                latitude=v["latitude"],
                longitude=v["longitude"],
                battery_percent=v["battery_percent"],
                online=True,
                status="IDLE",
                current_vertiport_id=v["current_vertiport_id"],
                updated_at=0,
            )
        )
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd server && python -m pytest tests/test_seed.py -v`
Expected: 1 passed。

- [ ] **Step 5: 提交**

```bash
git add server/app/seed.py server/tests/test_seed.py
git commit -m "feat(server): 停机坪与飞行器种子数据"
```

---

### Task 5: Pydantic schema（与 Android DTO 对齐）

**Files:**
- Create: `server/app/schemas.py`
- Create: `server/app/mappers.py`
- Create: `server/tests/test_schemas.py`

**Interfaces:**
- Produces schema：`GeoPointDto{latitude,longitude}`、`VertiportDto{id,name,location}`、
  `EvtolDto{id,name,location,batteryPercent,online,status}`、
  `PriceEstimateDto{amountCents,distanceKm,currency,cancellationFeeCents}`、
  `OrderDto{id,vehicleId,status,price,createdAt,updatedAt,pickup,pickupVertiport,vehicleOrigin,destination}`、
  `OccupancyDto{vertiportId,name,occupied,vehicles}`、
  请求体 `NearbyRequest{latitude,longitude,radiusKm}`、
  `EstimateRequest{pickup,destinationVertiportId}`、
  `CreateOrderRequest{pickup,destinationVertiportId,pickupVertiportId}`。
- Produces mapper：`vertiport_to_dto(Vertiport)->VertiportDto`、`vehicle_to_dto(Vehicle)->EvtolDto`、
  `order_to_dto(Order, pickup_vp, dest_vp)->OrderDto`。

- [ ] **Step 1: 写失败测试**

`server/tests/test_schemas.py`:
```python
from app.mappers import vehicle_to_dto, vertiport_to_dto
from app.models import Vehicle, Vertiport
from app.schemas import EvtolDto, OrderDto


def test_evtol_dto_json_keys_match_android():
    v = Vehicle(id="ev001", name="Eagle-01", latitude=29.5, longitude=106.5,
                battery_percent=88, online=True, status="IDLE",
                current_vertiport_id="vp001", updated_at=0)
    dto = vehicle_to_dto(v)
    data = dto.model_dump()
    assert set(data.keys()) == {"id", "name", "location", "batteryPercent", "online", "status"}
    assert data["location"] == {"latitude": 29.5, "longitude": 106.5}
    assert data["batteryPercent"] == 88


def test_vertiport_dto_shape():
    vp = Vertiport(id="vp001", name="坪", latitude=1.0, longitude=2.0)
    data = vertiport_to_dto(vp).model_dump()
    assert data == {"id": "vp001", "name": "坪", "location": {"latitude": 1.0, "longitude": 2.0}}


def test_order_dto_has_full_fields():
    fields = set(OrderDto.model_fields.keys())
    assert fields == {
        "id", "vehicleId", "status", "price", "createdAt", "updatedAt",
        "pickup", "pickupVertiport", "vehicleOrigin", "destination",
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd server && python -m pytest tests/test_schemas.py -v`
Expected: FAIL（`ModuleNotFoundError: app.schemas`）。

- [ ] **Step 3: 实现 schemas.py**

`server/app/schemas.py`:
```python
from pydantic import BaseModel


class GeoPointDto(BaseModel):
    latitude: float
    longitude: float


class VertiportDto(BaseModel):
    id: str
    name: str
    location: GeoPointDto


class EvtolDto(BaseModel):
    id: str
    name: str
    location: GeoPointDto
    batteryPercent: int
    online: bool
    status: str


class PriceEstimateDto(BaseModel):
    amountCents: int
    distanceKm: float
    currency: str
    cancellationFeeCents: int = 0


class OrderDto(BaseModel):
    id: str
    vehicleId: str
    status: str
    price: PriceEstimateDto
    createdAt: int
    updatedAt: int
    pickup: GeoPointDto
    pickupVertiport: VertiportDto
    vehicleOrigin: GeoPointDto
    destination: VertiportDto


class OccupancyDto(BaseModel):
    vertiportId: str
    name: str
    occupied: bool
    vehicles: list[EvtolDto]


class NearbyRequest(BaseModel):
    latitude: float
    longitude: float
    radiusKm: float = 15.0


class EstimateRequest(BaseModel):
    pickup: GeoPointDto
    destinationVertiportId: str


class CreateOrderRequest(BaseModel):
    pickup: GeoPointDto
    destinationVertiportId: str
    pickupVertiportId: str
```

- [ ] **Step 4: 实现 mappers.py**

`server/app/mappers.py`:
```python
from app.models import Order, Vehicle, Vertiport
from app.schemas import (
    EvtolDto,
    GeoPointDto,
    OrderDto,
    PriceEstimateDto,
    VertiportDto,
)


def vertiport_to_dto(vp: Vertiport) -> VertiportDto:
    return VertiportDto(
        id=vp.id,
        name=vp.name,
        location=GeoPointDto(latitude=vp.latitude, longitude=vp.longitude),
    )


def vehicle_to_dto(v: Vehicle) -> EvtolDto:
    return EvtolDto(
        id=v.id,
        name=v.name,
        location=GeoPointDto(latitude=v.latitude, longitude=v.longitude),
        batteryPercent=v.battery_percent,
        online=v.online,
        status=v.status,
    )


def order_to_dto(order: Order, pickup_vp: Vertiport, dest_vp: Vertiport) -> OrderDto:
    return OrderDto(
        id=order.id,
        vehicleId=order.vehicle_id,
        status=order.status,
        price=PriceEstimateDto(
            amountCents=order.amount_cents,
            distanceKm=order.distance_km,
            currency=order.currency,
            cancellationFeeCents=order.cancellation_fee_cents,
        ),
        createdAt=order.created_at,
        updatedAt=order.updated_at,
        pickup=GeoPointDto(latitude=order.pickup_lat, longitude=order.pickup_lng),
        pickupVertiport=vertiport_to_dto(pickup_vp),
        vehicleOrigin=GeoPointDto(
            latitude=order.vehicle_origin_lat, longitude=order.vehicle_origin_lng
        ),
        destination=vertiport_to_dto(dest_vp),
    )
```

- [ ] **Step 5: 跑测试确认通过**

Run: `cd server && python -m pytest tests/test_schemas.py -v`
Expected: 3 passed。

- [ ] **Step 6: 提交**

```bash
git add server/app/schemas.py server/app/mappers.py server/tests/test_schemas.py
git commit -m "feat(server): 与 Android DTO 对齐的 Pydantic schema 与映射"
```

---

### Task 6: 业务服务层（定价 / 派单 / 占用）

**Files:**
- Create: `server/app/services.py`
- Create: `server/tests/test_services.py`

**Interfaces:**
- Consumes: `app.geo`、`app.models`。
- Produces:
  `estimate_price(distance_km) -> tuple[int,float]`（返回 amount_cents, coerced_distance_km）；
  `nearest_vehicle_for(session, pickup_lat, pickup_lng) -> Vehicle | None`
  （筛选 online & status==IDLE & battery>=40，按到上车点距离取最近）；
  `vehicles_at_vertiport(session, vertiport_id) -> list[Vehicle]`；
  `now_millis() -> int`；`new_order_id() -> str`。
- 抛出：`NoAvailableVehicle(Exception)`。

- [ ] **Step 1: 写失败测试**

`server/tests/test_services.py`:
```python
from app.database import Base, make_engine, new_session
from app.models import Vehicle, Vertiport
from app.seed import seed_if_empty
from app.services import (
    estimate_price,
    nearest_vehicle_for,
    vehicles_at_vertiport,
)


def _seeded():
    engine = make_engine("sqlite://")
    Base.metadata.create_all(engine)
    session = new_session(engine)
    seed_if_empty(session)
    session.commit()
    return session


def test_estimate_price_matches_fake_formula():
    amount, dist = estimate_price(10.0)
    assert dist == 10.0
    assert amount == 2800 + int(10.0 * 760)


def test_estimate_price_floor_distance():
    amount, dist = estimate_price(0.2)
    assert dist == 1.0
    assert amount == 2800 + int(1.0 * 760)


def test_nearest_vehicle_picks_idle_charged():
    session = _seeded()
    # 上车点取 vp001(江北嘴) 坐标，ev002 就在该坪
    v = nearest_vehicle_for(session, 29.5792, 106.5758)
    assert v is not None
    assert v.id == "ev002"


def test_nearest_vehicle_none_when_low_battery():
    session = _seeded()
    for v in session.query(Vehicle).all():
        v.battery_percent = 10
    session.commit()
    assert nearest_vehicle_for(session, 29.5792, 106.5758) is None


def test_vehicles_at_vertiport():
    session = _seeded()
    at = vehicles_at_vertiport(session, "vp001")
    assert [v.id for v in at] == ["ev002"]
    assert vehicles_at_vertiport(session, "vp005") == []
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd server && python -m pytest tests/test_services.py -v`
Expected: FAIL（`ModuleNotFoundError: app.services`）。

- [ ] **Step 3: 实现 services.py**

`server/app/services.py`:
```python
import random
import time

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.geo import haversine_km
from app.models import Vehicle

BASE_FARE_CENTS = 2800
PER_KM_CENTS = 760
MIN_DISTANCE_KM = 1.0
MIN_BATTERY = 40


class NoAvailableVehicle(Exception):
    pass


def now_millis() -> int:
    return int(time.time() * 1000)


def new_order_id() -> str:
    return f"ODR-{str(now_millis())[-6:]}-{random.randint(100, 999)}"


def estimate_price(distance_km: float) -> tuple[int, float]:
    dist = max(distance_km, MIN_DISTANCE_KM)
    amount = BASE_FARE_CENTS + int(dist * PER_KM_CENTS)
    return amount, dist


def nearest_vehicle_for(session: Session, pickup_lat: float, pickup_lng: float) -> Vehicle | None:
    candidates = session.execute(
        select(Vehicle).where(
            Vehicle.online.is_(True),
            Vehicle.status == "IDLE",
            Vehicle.battery_percent >= MIN_BATTERY,
        )
    ).scalars().all()
    if not candidates:
        return None
    return min(
        candidates,
        key=lambda v: haversine_km(v.latitude, v.longitude, pickup_lat, pickup_lng),
    )


def vehicles_at_vertiport(session: Session, vertiport_id: str) -> list[Vehicle]:
    return session.execute(
        select(Vehicle).where(
            Vehicle.current_vertiport_id == vertiport_id,
            Vehicle.online.is_(True),
        )
    ).scalars().all()
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd server && python -m pytest tests/test_services.py -v`
Expected: 5 passed。

- [ ] **Step 5: 提交**

```bash
git add server/app/services.py server/tests/test_services.py
git commit -m "feat(server): 定价/派单/占用 业务服务层"
```

---

### Task 7: app 端只读接口（vertiports / vehicles / nearby / occupancy）

**Files:**
- Create: `server/app/routers/__init__.py`
- Create: `server/app/routers/mobile.py`
- Modify: `server/app/main.py`
- Modify: `server/tests/conftest.py`
- Create: `server/tests/test_mobile_read.py`

**Interfaces:**
- Consumes: `app.database.get_session`、`app.mappers`、`app.services`、`app.schemas`。
- Produces 路由（挂在 app 根，无前缀）：
  `GET /vertiports`、`GET /vehicles`、`POST /vehicles/nearby`、
  `GET /vertiports/{id}/occupancy`。

- [ ] **Step 1: 更新测试夹具为隔离内存库**

把 `server/tests/conftest.py` 整体替换为：
```python
import os
import sys

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))

import pytest
from fastapi.testclient import TestClient
from sqlalchemy.orm import sessionmaker

import app.database as database
from app.database import Base, make_engine
from app.main import app
from app.seed import seed_if_empty


@pytest.fixture
def client():
    engine = make_engine("sqlite://")
    Base.metadata.create_all(engine)
    database._engine = engine
    database.SessionLocal = sessionmaker(bind=engine, expire_on_commit=False)
    with database.SessionLocal() as s:
        seed_if_empty(s)
        s.commit()
    yield TestClient(app)
```

- [ ] **Step 2: 写失败测试**

`server/tests/test_mobile_read.py`:
```python
def test_get_vertiports(client):
    resp = client.get("/vertiports")
    assert resp.status_code == 200
    data = resp.json()
    assert len(data) == 12
    assert data[0]["location"]["latitude"] == 29.5792


def test_nearby_returns_three_sorted(client):
    resp = client.post("/vehicles/nearby", json={"latitude": 29.5792, "longitude": 106.5758})
    assert resp.status_code == 200
    data = resp.json()
    assert len(data) == 3
    assert data[0]["id"] == "ev002"  # 江北嘴最近
    assert "batteryPercent" in data[0]


def test_occupancy_occupied(client):
    resp = client.get("/vertiports/vp001/occupancy")
    assert resp.status_code == 200
    data = resp.json()
    assert data["vertiportId"] == "vp001"
    assert data["occupied"] is True
    assert data["vehicles"][0]["id"] == "ev002"


def test_occupancy_empty(client):
    resp = client.get("/vertiports/vp005/occupancy")
    assert resp.json()["occupied"] is False
    assert resp.json()["vehicles"] == []


def test_occupancy_404(client):
    assert client.get("/vertiports/nope/occupancy").status_code == 404
```

- [ ] **Step 3: 跑测试确认失败**

Run: `cd server && python -m pytest tests/test_mobile_read.py -v`
Expected: FAIL（404 路由不存在）。

- [ ] **Step 4: 实现 mobile 路由**

`server/app/routers/__init__.py`：空文件。

`server/app/routers/mobile.py`:
```python
from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.database import get_session
from app.geo import haversine_km
from app.mappers import vehicle_to_dto, vertiport_to_dto
from app.models import Vehicle, Vertiport
from app.schemas import EvtolDto, NearbyRequest, OccupancyDto, VertiportDto
from app.services import vehicles_at_vertiport

router = APIRouter()


@router.get("/vertiports", response_model=list[VertiportDto])
def get_vertiports(session: Session = Depends(get_session)):
    rows = session.execute(select(Vertiport)).scalars().all()
    return [vertiport_to_dto(v) for v in rows]


@router.get("/vehicles", response_model=list[EvtolDto])
def get_vehicles(session: Session = Depends(get_session)):
    rows = session.execute(select(Vehicle)).scalars().all()
    return [vehicle_to_dto(v) for v in rows]


@router.post("/vehicles/nearby", response_model=list[EvtolDto])
def nearby(req: NearbyRequest, session: Session = Depends(get_session)):
    rows = session.execute(select(Vehicle)).scalars().all()
    rows.sort(key=lambda v: haversine_km(v.latitude, v.longitude, req.latitude, req.longitude))
    return [vehicle_to_dto(v) for v in rows[:3]]


@router.get("/vertiports/{vertiport_id}/occupancy", response_model=OccupancyDto)
def occupancy(vertiport_id: str, session: Session = Depends(get_session)):
    vp = session.get(Vertiport, vertiport_id)
    if vp is None:
        raise HTTPException(status_code=404, detail="vertiport not found")
    at = vehicles_at_vertiport(session, vertiport_id)
    return OccupancyDto(
        vertiportId=vp.id,
        name=vp.name,
        occupied=len(at) > 0,
        vehicles=[vehicle_to_dto(v) for v in at],
    )
```

- [ ] **Step 5: 把路由挂到 app**

把 `server/app/main.py` 替换为：
```python
from fastapi import FastAPI

from app.routers import mobile

app = FastAPI(title="eVTOL Dispatch Server")
app.include_router(mobile.router)


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}
```

- [ ] **Step 6: 跑测试确认通过**

Run: `cd server && python -m pytest tests/test_mobile_read.py tests/test_health.py -v`
Expected: 全部 passed。

- [ ] **Step 7: 提交**

```bash
git add server/app/routers server/app/main.py server/tests/conftest.py server/tests/test_mobile_read.py
git commit -m "feat(server): app 端只读接口与停机坪占用查询"
```

---

### Task 8: 定价与下单接口（price / orders 创建与读取）

**Files:**
- Modify: `server/app/routers/mobile.py`
- Create: `server/tests/test_mobile_orders.py`

**Interfaces:**
- Produces 路由：`POST /price/estimate`、`POST /orders`、`GET /orders/{id}`、`GET /orders`。
- 下单逻辑：取 `pickupVertiportId` 对应坪坐标派最近可用车；无车 → 409；
  车置 RESERVED 并清空 `current_vertiport_id`（起飞离坪）；订单 status=RESERVED，
  `vehicle_origin` = 车当前位置；价格按上车坪→目的坪距离计算。

- [ ] **Step 1: 写失败测试**

`server/tests/test_mobile_orders.py`:
```python
def test_estimate_price(client):
    resp = client.post(
        "/price/estimate",
        json={"pickup": {"latitude": 29.5792, "longitude": 106.5758},
              "destinationVertiportId": "vp006"},
    )
    assert resp.status_code == 200
    body = resp.json()
    assert body["currency"] == "CNY"
    assert body["amountCents"] > 2800


def test_create_order_and_fetch(client):
    resp = client.post(
        "/orders",
        json={"pickup": {"latitude": 29.5792, "longitude": 106.5758},
              "destinationVertiportId": "vp006",
              "pickupVertiportId": "vp001"},
    )
    assert resp.status_code == 200
    order = resp.json()
    assert order["status"] == "RESERVED"
    assert order["vehicleId"] == "ev002"
    assert order["pickupVertiport"]["id"] == "vp001"
    assert order["destination"]["id"] == "vp006"
    # 车已离坪
    occ = client.get("/vertiports/vp001/occupancy").json()
    assert occ["occupied"] is False
    # 可按 id 取回
    again = client.get(f"/orders/{order['id']}")
    assert again.status_code == 200
    assert again.json()["id"] == order["id"]
    # 出现在历史
    history = client.get("/orders").json()
    assert any(o["id"] == order["id"] for o in history)


def test_create_order_no_vehicle_returns_409(client):
    # 把所有车改为离线
    client_get = client.get("/vehicles").json()
    assert client_get  # 有车
    # 直接连下 4 单，第 4 单应 409（只有 3 架车）
    statuses = []
    for _ in range(4):
        r = client.post(
            "/orders",
            json={"pickup": {"latitude": 29.5792, "longitude": 106.5758},
                  "destinationVertiportId": "vp006",
                  "pickupVertiportId": "vp001"},
        )
        statuses.append(r.status_code)
    assert statuses.count(409) >= 1


def test_get_order_404(client):
    assert client.get("/orders/nope").status_code == 404
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd server && python -m pytest tests/test_mobile_orders.py -v`
Expected: FAIL（路由不存在）。

- [ ] **Step 3: 在 mobile.py 追加下单相关路由**

在 `server/app/routers/mobile.py` 顶部 import 增补：
```python
from app.mappers import order_to_dto
from app.models import Order
from app.schemas import CreateOrderRequest, EstimateRequest, OrderDto, PriceEstimateDto
from app.services import (
    NoAvailableVehicle,
    estimate_price,
    nearest_vehicle_for,
    new_order_id,
    now_millis,
)
```

在文件末尾追加：
```python
def _load_order_dto(session: Session, order: Order) -> OrderDto:
    pickup_vp = session.get(Vertiport, order.pickup_vertiport_id)
    dest_vp = session.get(Vertiport, order.destination_id)
    return order_to_dto(order, pickup_vp, dest_vp)


@router.post("/price/estimate", response_model=PriceEstimateDto)
def price_estimate(req: EstimateRequest, session: Session = Depends(get_session)):
    dest = session.get(Vertiport, req.destinationVertiportId)
    if dest is None:
        raise HTTPException(status_code=404, detail="destination not found")
    distance = haversine_km(req.pickup.latitude, req.pickup.longitude, dest.latitude, dest.longitude)
    amount, dist = estimate_price(distance)
    return PriceEstimateDto(amountCents=amount, distanceKm=dist, currency="CNY", cancellationFeeCents=0)


@router.post("/orders", response_model=OrderDto)
def create_order(req: CreateOrderRequest, session: Session = Depends(get_session)):
    pickup_vp = session.get(Vertiport, req.pickupVertiportId)
    dest_vp = session.get(Vertiport, req.destinationVertiportId)
    if pickup_vp is None or dest_vp is None:
        raise HTTPException(status_code=404, detail="vertiport not found")
    vehicle = nearest_vehicle_for(session, pickup_vp.latitude, pickup_vp.longitude)
    if vehicle is None:
        raise HTTPException(status_code=409, detail="no available vehicle")

    distance = haversine_km(pickup_vp.latitude, pickup_vp.longitude, dest_vp.latitude, dest_vp.longitude)
    amount, dist = estimate_price(distance)
    now = now_millis()

    vehicle.status = "RESERVED"
    vehicle.current_vertiport_id = None
    vehicle.updated_at = now

    order = Order(
        id=new_order_id(),
        pickup_lat=req.pickup.latitude,
        pickup_lng=req.pickup.longitude,
        pickup_vertiport_id=pickup_vp.id,
        vehicle_origin_lat=vehicle.latitude,
        vehicle_origin_lng=vehicle.longitude,
        destination_id=dest_vp.id,
        vehicle_id=vehicle.id,
        status="RESERVED",
        amount_cents=amount,
        distance_km=dist,
        currency="CNY",
        cancellation_fee_cents=0,
        created_at=now,
        updated_at=now,
    )
    session.add(order)
    session.commit()
    session.refresh(order)
    return _load_order_dto(session, order)


@router.get("/orders", response_model=list[OrderDto])
def list_orders(session: Session = Depends(get_session)):
    rows = session.execute(select(Order).order_by(Order.created_at.desc())).scalars().all()
    return [_load_order_dto(session, o) for o in rows]


@router.get("/orders/{order_id}", response_model=OrderDto)
def get_order(order_id: str, session: Session = Depends(get_session)):
    order = session.get(Order, order_id)
    if order is None:
        raise HTTPException(status_code=404, detail="order not found")
    return _load_order_dto(session, order)
```

> 注意：`NoAvailableVehicle` 已 import 备用；此处直接抛 409。保留 import 供 services 复用。
> 若 linter 报未使用，可删除该行 import。

- [ ] **Step 4: 跑测试确认通过**

Run: `cd server && python -m pytest tests/test_mobile_orders.py -v`
Expected: 4 passed。

- [ ] **Step 5: 提交**

```bash
git add server/app/routers/mobile.py server/tests/test_mobile_orders.py
git commit -m "feat(server): 定价与下单/订单读取接口"
```

---

### Task 9: 登机与取消接口

**Files:**
- Modify: `server/app/routers/mobile.py`
- Create: `server/tests/test_mobile_lifecycle_endpoints.py`

**Interfaces:**
- Produces 路由：`POST /orders/{id}/board`、`POST /orders/{id}/cancel`。
- board：仅当订单 status==BOARDING 时合法，置 IN_FLIGHT（车 IN_FLIGHT）；否则 409。
- cancel：仅当 status ∈ {CREATED,ASSIGNED,RESERVED,BOARDING} 合法，置 RETURNING，
  按行程进度算违约金（与 Fake 一致，下限 1200 分）；否则 409。

- [ ] **Step 1: 写失败测试**

`server/tests/test_mobile_lifecycle_endpoints.py`:
```python
def _create(client):
    return client.post(
        "/orders",
        json={"pickup": {"latitude": 29.5792, "longitude": 106.5758},
              "destinationVertiportId": "vp006",
              "pickupVertiportId": "vp001"},
    ).json()


def test_board_rejected_when_not_boarding(client):
    order = _create(client)  # 初始 RESERVED
    resp = client.post(f"/orders/{order['id']}/board")
    assert resp.status_code == 409


def test_cancel_when_reserved_sets_returning(client):
    order = _create(client)
    resp = client.post(f"/orders/{order['id']}/cancel")
    assert resp.status_code == 200
    body = resp.json()
    assert body["status"] == "RETURNING"
    assert body["price"]["cancellationFeeCents"] >= 1200


def test_cancel_unknown_404(client):
    assert client.post("/orders/nope/cancel").status_code == 404
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd server && python -m pytest tests/test_mobile_lifecycle_endpoints.py -v`
Expected: FAIL。

- [ ] **Step 3: 追加 board / cancel 路由**

在 `server/app/routers/mobile.py` 文件末尾追加：
```python
CANCELABLE = {"CREATED", "ASSIGNED", "RESERVED", "BOARDING"}


@router.post("/orders/{order_id}/board", response_model=OrderDto)
def board(order_id: str, session: Session = Depends(get_session)):
    order = session.get(Order, order_id)
    if order is None:
        raise HTTPException(status_code=404, detail="order not found")
    if order.status != "BOARDING":
        raise HTTPException(status_code=409, detail="order not in BOARDING")
    vehicle = session.get(Vehicle, order.vehicle_id)
    now = now_millis()
    order.status = "IN_FLIGHT"
    order.updated_at = now
    if vehicle is not None:
        vehicle.status = "IN_FLIGHT"
        vehicle.updated_at = now
    session.commit()
    session.refresh(order)
    return _load_order_dto(session, order)


@router.post("/orders/{order_id}/cancel", response_model=OrderDto)
def cancel(order_id: str, session: Session = Depends(get_session)):
    order = session.get(Order, order_id)
    if order is None:
        raise HTTPException(status_code=404, detail="order not found")
    if order.status not in CANCELABLE:
        raise HTTPException(status_code=409, detail="order not cancelable")
    vehicle = session.get(Vehicle, order.vehicle_id)
    now = now_millis()

    pickup_route_km = haversine_km(
        order.vehicle_origin_lat, order.vehicle_origin_lng,
        *(session.get(Vertiport, order.pickup_vertiport_id).latitude,
          session.get(Vertiport, order.pickup_vertiport_id).longitude),
    )
    traveled_km = 0.0
    if vehicle is not None:
        traveled_km = min(
            haversine_km(order.vehicle_origin_lat, order.vehicle_origin_lng,
                         vehicle.latitude, vehicle.longitude),
            pickup_route_km,
        )
    progress = (traveled_km / pickup_route_km) if pickup_route_km > 0 else 0.0
    fee = int(1200 + order.amount_cents * 0.45 * min(max(progress, 0.0), 1.0))
    fee = max(fee, 1200)

    order.status = "RETURNING"
    order.cancellation_fee_cents = fee
    order.updated_at = now
    if vehicle is not None:
        vehicle.status = "RESERVED"
        vehicle.updated_at = now
    session.commit()
    session.refresh(order)
    return _load_order_dto(session, order)
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd server && python -m pytest tests/test_mobile_lifecycle_endpoints.py -v`
Expected: 3 passed。

- [ ] **Step 5: 提交**

```bash
git add server/app/routers/mobile.py server/tests/test_mobile_lifecycle_endpoints.py
git commit -m "feat(server): 登机确认与取消接口"
```

---

### Task 10: 调度模拟引擎（单步 tick）

**Files:**
- Create: `server/app/simulation.py`
- Create: `server/tests/test_simulation.py`

**Interfaces:**
- Consumes: `app.geo.step_toward`、`app.models`、`app.services.now_millis`。
- Produces: `tick(session, dt_seconds: float = 1.0) -> None`——推进所有进行中订单：
  - RESERVED：车飞向上车坪（95km/h）；到达→ BOARDING（车 BOARDING）。
  - IN_FLIGHT：车飞向目的坪（180km/h）；到达→ DONE，车 IDLE、停靠目的坪、电量-。
  - RETURNING：车飞向最近坪（110km/h）；到达→ CANCELED，车 IDLE、停靠该坪。
  - BOARDING：不移动（等 /board）。
  - 移动时电量随里程递减，下限 10。
- Produces: `SPEEDS = {"RESERVED":95.0,"IN_FLIGHT":180.0,"RETURNING":110.0}`、
  `ARRIVE_KM = 0.08`、`PARK_KM = 0.08`。

- [ ] **Step 1: 写失败测试**

`server/tests/test_simulation.py`:
```python
from app.database import Base, make_engine, new_session
from app.models import Order, Vehicle, Vertiport
from app.seed import seed_if_empty
from app.services import now_millis
from app.simulation import tick


def _order_in_status(session, status, vehicle_id, pickup="vp001", dest="vp006"):
    v = session.get(Vehicle, vehicle_id)
    v.status = "RESERVED" if status == "RESERVED" else "IN_FLIGHT"
    pvp = session.get(Vertiport, pickup)
    now = now_millis()
    o = Order(
        id=f"O-{status}", pickup_lat=pvp.latitude, pickup_lng=pvp.longitude,
        pickup_vertiport_id=pickup, vehicle_origin_lat=v.latitude, vehicle_origin_lng=v.longitude,
        destination_id=dest, vehicle_id=vehicle_id, status=status,
        amount_cents=5000, distance_km=10.0, currency="CNY", cancellation_fee_cents=0,
        created_at=now, updated_at=now,
    )
    session.add(o)
    session.commit()
    return o


def _seeded():
    engine = make_engine("sqlite://")
    Base.metadata.create_all(engine)
    s = new_session(engine)
    seed_if_empty(s)
    s.commit()
    return s


def test_reserved_vehicle_moves_toward_pickup():
    s = _seeded()
    # 让 ev003(两江机场) 接 vp001 的单，距离远，单步不会到达
    v = s.get(Vehicle, "ev003")
    v.current_vertiport_id = None
    s.commit()
    _order_in_status(s, "RESERVED", "ev003", pickup="vp001")
    before = (v.latitude, v.longitude)
    tick(s, dt_seconds=1.0)
    s.refresh(v)
    assert (v.latitude, v.longitude) != before  # 移动了
    assert v.status == "RESERVED"


def test_reserved_arrives_becomes_boarding():
    s = _seeded()
    v = s.get(Vehicle, "ev002")  # 就在 vp001
    v.current_vertiport_id = None
    v.status = "RESERVED"
    s.commit()
    _order_in_status(s, "RESERVED", "ev002", pickup="vp001")
    tick(s, dt_seconds=1.0)
    order = s.get(Order, "O-RESERVED")
    s.refresh(v)
    assert order.status == "BOARDING"
    assert v.status == "BOARDING"


def test_in_flight_arrival_completes_and_parks():
    s = _seeded()
    v = s.get(Vehicle, "ev003")  # 两江机场=vp006，目的也 vp006 → 立即到达
    v.status = "IN_FLIGHT"
    v.current_vertiport_id = None
    s.commit()
    _order_in_status(s, "IN_FLIGHT", "ev003", pickup="vp001", dest="vp006")
    tick(s, dt_seconds=1.0)
    order = s.get(Order, "O-IN_FLIGHT")
    s.refresh(v)
    assert order.status == "DONE"
    assert v.status == "IDLE"
    assert v.current_vertiport_id == "vp006"
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd server && python -m pytest tests/test_simulation.py -v`
Expected: FAIL（`ModuleNotFoundError: app.simulation`）。

- [ ] **Step 3: 实现 simulation.py**

`server/app/simulation.py`:
```python
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.geo import haversine_km, step_toward
from app.models import Order, Vehicle, Vertiport
from app.services import now_millis

SPEEDS = {"RESERVED": 95.0, "IN_FLIGHT": 180.0, "RETURNING": 110.0}
ARRIVE_KM = 0.08
ACTIVE = {"RESERVED", "BOARDING", "IN_FLIGHT", "RETURNING"}


def _nearest_vertiport(session: Session, lat: float, lng: float) -> Vertiport:
    ports = session.execute(select(Vertiport)).scalars().all()
    return min(ports, key=lambda p: haversine_km(lat, lng, p.latitude, p.longitude))


def _drain(vehicle: Vehicle, km: float) -> None:
    drop = int(km * 0.3)
    vehicle.battery_percent = max(vehicle.battery_percent - drop, 10)


def tick(session: Session, dt_seconds: float = 1.0) -> None:
    now = now_millis()
    orders = session.execute(
        select(Order).where(Order.status.in_(ACTIVE))
    ).scalars().all()
    for order in orders:
        vehicle = session.get(Vehicle, order.vehicle_id)
        if vehicle is None:
            continue

        if order.status == "BOARDING":
            continue

        speed = SPEEDS.get(order.status)
        if speed is None:
            continue
        max_km = speed / 3600.0 * dt_seconds

        if order.status == "RESERVED":
            target = session.get(Vertiport, order.pickup_vertiport_id)
        elif order.status == "IN_FLIGHT":
            target = session.get(Vertiport, order.destination_id)
        else:  # RETURNING
            target = _nearest_vertiport(session, vehicle.latitude, vehicle.longitude)

        prev = (vehicle.latitude, vehicle.longitude)
        new_lat, new_lng, arrived = step_toward(
            vehicle.latitude, vehicle.longitude, target.latitude, target.longitude, max_km
        )
        vehicle.latitude, vehicle.longitude = new_lat, new_lng
        _drain(vehicle, haversine_km(prev[0], prev[1], new_lat, new_lng))
        vehicle.updated_at = now
        order.updated_at = now

        if not arrived:
            continue

        if order.status == "RESERVED":
            order.status = "BOARDING"
            vehicle.status = "BOARDING"
            vehicle.current_vertiport_id = target.id
        elif order.status == "IN_FLIGHT":
            order.status = "DONE"
            vehicle.status = "IDLE"
            vehicle.current_vertiport_id = target.id
        elif order.status == "RETURNING":
            order.status = "CANCELED"
            vehicle.status = "IDLE"
            vehicle.current_vertiport_id = target.id

    session.commit()
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd server && python -m pytest tests/test_simulation.py -v`
Expected: 3 passed。

- [ ] **Step 5: 提交**

```bash
git add server/app/simulation.py server/tests/test_simulation.py
git commit -m "feat(server): 调度模拟引擎单步推进逻辑"
```

---

### Task 11: 引擎后台循环接入 FastAPI 生命周期 + 端到端测试

**Files:**
- Modify: `server/app/main.py`
- Create: `server/app/engine_loop.py`
- Create: `server/tests/test_e2e_lifecycle.py`

**Interfaces:**
- Consumes: `app.simulation.tick`、`app.database.session_scope`。
- Produces: `app.engine_loop.run_engine(stop_event, interval=1.0)`（async）、
  `app.engine_loop.engine_lifespan`（FastAPI lifespan，启动时 seed + 拉起后台任务，
  关闭时停任务）。

- [ ] **Step 1: 写失败的端到端测试（手动驱动 tick，不依赖真实计时）**

`server/tests/test_e2e_lifecycle.py`:
```python
import app.database as database
from app.simulation import tick


def _create(client):
    return client.post(
        "/orders",
        json={"pickup": {"latitude": 29.5792, "longitude": 106.5758},
              "destinationVertiportId": "vp006",
              "pickupVertiportId": "vp001"},
    ).json()


def _drive(max_ticks=2000):
    # 用与请求同一个引擎的 session 反复 tick，直到无活动订单
    for _ in range(max_ticks):
        with database.session_scope() as s:
            from sqlalchemy import select
            from app.models import Order
            active = s.execute(
                select(Order).where(Order.status.in_(["RESERVED", "IN_FLIGHT", "RETURNING"]))
            ).scalars().all()
            if not active:
                return
            tick(s, dt_seconds=5.0)


def test_full_trip_reaches_done(client):
    order = _create(client)
    oid = order["id"]
    # 推进到 BOARDING
    for _ in range(2000):
        with database.session_scope() as s:
            tick(s, dt_seconds=5.0)
        cur = client.get(f"/orders/{oid}").json()
        if cur["status"] == "BOARDING":
            break
    assert client.get(f"/orders/{oid}").json()["status"] == "BOARDING"
    # 确认登机 → 推进到 DONE
    assert client.post(f"/orders/{oid}/board").status_code == 200
    _drive()
    final = client.get(f"/orders/{oid}").json()
    assert final["status"] == "DONE"
    # 目的坪现在有车
    assert client.get("/vertiports/vp006/occupancy").json()["occupied"] is True
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd server && python -m pytest tests/test_e2e_lifecycle.py -v`
Expected: FAIL（`engine_loop` 不存在；或断言不满足）。

- [ ] **Step 3: 实现 engine_loop.py**

`server/app/engine_loop.py`:
```python
import asyncio
from contextlib import asynccontextmanager

from fastapi import FastAPI

from app.config import get_settings
from app.database import init_app_engine, session_scope
from app.seed import seed_if_empty
from app.simulation import tick

ENGINE_INTERVAL_SECONDS = 1.0


async def run_engine(stop_event: asyncio.Event, interval: float = ENGINE_INTERVAL_SECONDS) -> None:
    while not stop_event.is_set():
        with session_scope() as session:
            tick(session, dt_seconds=interval)
        try:
            await asyncio.wait_for(stop_event.wait(), timeout=interval)
        except asyncio.TimeoutError:
            pass


@asynccontextmanager
async def engine_lifespan(app: FastAPI):
    settings = get_settings()
    init_app_engine(settings.db_path)
    with session_scope() as session:
        seed_if_empty(session)
    stop_event = asyncio.Event()
    task = asyncio.create_task(run_engine(stop_event))
    try:
        yield
    finally:
        stop_event.set()
        await task
```

- [ ] **Step 4: 把 lifespan 接到 app；测试模式下不自动启动后台循环**

把 `server/app/main.py` 替换为：
```python
import os

from fastapi import FastAPI

from app.engine_loop import engine_lifespan
from app.routers import mobile

# 测试时由 conftest 自行管理引擎与库，避免后台循环与测试 tick 抢库
_TESTING = os.getenv("EVTOL_TESTING") == "1"

app = FastAPI(
    title="eVTOL Dispatch Server",
    lifespan=None if _TESTING else engine_lifespan,
)
app.include_router(mobile.router)


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}
```

在 `server/tests/conftest.py` 顶部（其它 import 之前）加：
```python
import os
os.environ["EVTOL_TESTING"] = "1"
```

- [ ] **Step 5: 跑全部测试**

Run: `cd server && python -m pytest -v`
Expected: 全部 passed（含端到端到 DONE）。

- [ ] **Step 6: 提交**

```bash
git add server/app/engine_loop.py server/app/main.py server/tests/conftest.py server/tests/test_e2e_lifecycle.py
git commit -m "feat(server): 引擎后台循环接入生命周期与端到端验证"
```

---

### Task 12: 本地运行与容器化

**Files:**
- Create: `server/Dockerfile`
- Create: `server/docker-compose.yml`
- Create: `server/.dockerignore`
- Create: `server/README.md`
- Create: `server/run_local.md`（运行速查）

**Interfaces:**
- Produces: 可 `uvicorn app.main:app` 启动的服务；Docker 镜像单 worker 运行。

- [ ] **Step 1: 写 Dockerfile（单 worker）**

`server/Dockerfile`:
```dockerfile
FROM python:3.12-slim
WORKDIR /srv
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY app ./app
ENV DB_PATH=/data/evtol.db
EXPOSE 8000
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000", "--workers", "1"]
```

`server/.dockerignore`:
```
tests/
__pycache__/
*.pyc
.env
*.db
```

- [ ] **Step 2: 写 docker-compose（挂载数据卷）**

`server/docker-compose.yml`:
```yaml
services:
  api:
    build: .
    ports:
      - "8000:8000"
    environment:
      - DB_PATH=/data/evtol.db
      - ADMIN_USERNAME=${ADMIN_USERNAME:-admin}
      - ADMIN_PASSWORD=${ADMIN_PASSWORD:-change-me-please}
      - SECRET_KEY=${SECRET_KEY:-dev-secret}
      - APP_API_KEY=${APP_API_KEY:-}
    volumes:
      - evtol-data:/data
    restart: unless-stopped

volumes:
  evtol-data:
```

- [ ] **Step 3: 写 README**

`server/README.md`：包含本地运行、测试、Docker 运行、单 worker 约束说明、
`.env` 变量表、接口清单（与 §6 一致）。`server/run_local.md`：
```
# 本地开发
cd server
python -m pip install -r requirements.txt
copy .env.example .env   # Windows；Linux: cp
python -m uvicorn app.main:app --reload --port 8000

# 跑测试
python -m pytest -v

# Docker
docker compose up --build
```

- [ ] **Step 4: 冒烟验证（本地起服务，手测两个接口）**

Run:
```bash
cd server && python -m uvicorn app.main:app --port 8000 &
sleep 3
curl -s http://127.0.0.1:8000/vertiports | head -c 200
curl -s http://127.0.0.1:8000/vertiports/vp001/occupancy
```
Expected: 返回 12 个停机坪 JSON；vp001 occupancy 含 ev002，`"occupied":true`。
（验证后停掉该后台进程。）

- [ ] **Step 5: 提交**

```bash
git add server/Dockerfile server/docker-compose.yml server/.dockerignore server/README.md server/run_local.md
git commit -m "chore(server): 容器化与本地运行文档"
```

---

## Self-Review（对照设计文档）

**Spec coverage（§13 阶段 1+2 + §6 app 接口 + 占用）：**
- §6.1 保留接口：vertiports(Task7)、nearby(Task7)、price/estimate(Task8)、orders 创建(Task8)、cancel(Task9) ✓
- §6.2 新增接口：GET /orders/{id}(Task8)、board(Task9)、GET /orders(Task8)、occupancy(Task7)、GET /vehicles(Task7) ✓
- §5 数据模型（含 admin_users 建表，鉴权逻辑在计划二）：Task3 ✓
- §6.3 DTO 补全（pickupVertiport/vehicleOrigin/cancellationFeeCents）：Task5 ✓
- §8 模拟引擎（生命周期、占用字段维护）：Task10、Task11 ✓
- §11 容器化 + 单 worker：Task12 ✓
- §12 测试（契约/引擎/端到端）：贯穿各 Task ✓
- 留待计划二/三：Web 管理台、鉴权校验、公网 HTTPS（计划二）；Android 接入（计划三）。

**Placeholder scan：** 无 TBD/TODO；每步含可运行代码与命令。Task8 对 `NoAvailableVehicle`
import 给了明确处置说明（备用，可删）。

**Type consistency：** `make_engine`/`new_session`/`init_app_engine`/`session_scope`
在 Task3 定义，后续 Task 一致使用；`tick(session, dt_seconds)` 签名 Task10 定义、
Task11 一致调用；DTO 字段名跨 Task5/7/8/9 一致。

**已知注意点（实现者须知）：**
- Task11 端到端测试与后台循环：测试用 `EVTOL_TESTING=1` 关闭 lifespan 后台循环，
  改由测试手动 `tick`，避免两处同时写 SQLite。生产由 lifespan 驱动。
- SQLite 内存库（`sqlite://`）在测试中每个 fixture 独立；生产用文件库走 WAL。

---

## 后续计划预览（本计划完成后展开为同等粒度）

**计划二 · Web 管理台 + 鉴权 + 公网部署**：admin_users 播种与 bcrypt 校验、
登录签发 Cookie/Bearer、`/admin/*` 守卫；管理 API（车辆/停机坪 CRUD、订单干预、
强制改状态）；多页 Jinja2（登录/总览大屏/机队/停机坪/订单）+ 高德 JS 地图 + 2s 轮询；
Caddy 反代 + 自动 HTTPS + 部署脚本。

**计划三 · Android 接入**：扩展 `DispatchApi`（get order / board / history / occupancy）
与 DTO（补 pickupVertiport/vehicleOrigin/cancellationFeeCents）；新增
`RemoteDispatchRepository` 并在 `AppModule` 切绑（保留 Fake 开关）；`observeOrder`
改 1.5s 轮询；机队轮询 160ms→~1s + UI 插值；`SERVER_BASE_URL` 配置化；Room 离线缓存合并。
