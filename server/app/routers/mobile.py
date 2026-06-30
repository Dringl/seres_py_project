import threading

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.auth import hash_password, verify_password
from app.database import get_session
from app.geo import haversine_km
from app.mappers import order_to_dto, vehicle_to_dto, vertiport_to_dto
from app.mobile_auth import get_current_user, issue_token
from app.models import Order, User, Vehicle, Vertiport
from app.schemas import (
    AuthResponse,
    CreateOrderRequest,
    EstimateRequest,
    EvtolDto,
    LoginRequest,
    NearbyRequest,
    OccupancyDto,
    OrderDto,
    PriceEstimateDto,
    RegisterRequest,
    VertiportDto,
)
from app.services import (
    estimate_price,
    nearest_vehicle_for,
    new_order_id,
    now_millis,
    vehicles_at_vertiport,
)

# 开放路由：注册/登录无需登录态
auth_router = APIRouter(prefix="/auth")


@auth_router.post("/register", response_model=AuthResponse)
def register(req: RegisterRequest, session: Session = Depends(get_session)):
    username = req.username.strip()
    if len(username) < 3 or len(req.password) < 6:
        raise HTTPException(status_code=422, detail="用户名至少 3 位，密码至少 6 位")
    exists = session.execute(select(User).where(User.username == username)).first()
    if exists is not None:
        raise HTTPException(status_code=409, detail="用户名已被占用")
    user = User(username=username, password_hash=hash_password(req.password), created_at=now_millis())
    session.add(user)
    session.commit()
    session.refresh(user)
    return AuthResponse(token=issue_token(user.id), userId=user.id, username=user.username)


@auth_router.post("/login", response_model=AuthResponse)
def login(req: LoginRequest, session: Session = Depends(get_session)):
    username = req.username.strip()
    user = session.execute(select(User).where(User.username == username)).scalar_one_or_none()
    if user is None or not verify_password(req.password, user.password_hash):
        raise HTTPException(status_code=401, detail="用户名或密码错误")
    return AuthResponse(token=issue_token(user.id), userId=user.id, username=user.username)


@auth_router.get("/me", response_model=AuthResponse)
def me(user: User = Depends(get_current_user)):
    return AuthResponse(token="", userId=user.id, username=user.username)


# 受保护路由：以下所有接口都要求登录（未登录禁止查看任何信息）
router = APIRouter(dependencies=[Depends(get_current_user)])

# 串行化下单的"选车+预约"临界区（单 worker 进程内有效），防止并发重复派单
_create_order_lock = threading.Lock()


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
def create_order(
    req: CreateOrderRequest,
    session: Session = Depends(get_session),
    user: User = Depends(get_current_user),
):
    pickup_vp = session.get(Vertiport, req.pickupVertiportId)
    dest_vp = session.get(Vertiport, req.destinationVertiportId)
    if pickup_vp is None or dest_vp is None:
        raise HTTPException(status_code=404, detail="vertiport not found")

    distance = haversine_km(pickup_vp.latitude, pickup_vp.longitude, dest_vp.latitude, dest_vp.longitude)
    amount, dist = estimate_price(distance)

    # 选车→预约→落库 串行化，避免两个并发请求把同一空闲飞行器重复派单
    with _create_order_lock:
        vehicle = nearest_vehicle_for(session, pickup_vp.latitude, pickup_vp.longitude)
        if vehicle is None:
            raise HTTPException(status_code=409, detail="no available vehicle")
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
            user_id=user.id,
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
def list_orders(
    session: Session = Depends(get_session),
    user: User = Depends(get_current_user),
):
    rows = session.execute(
        select(Order).where(Order.user_id == user.id).order_by(Order.created_at.desc())
    ).scalars().all()
    return [_load_order_dto(session, o) for o in rows]


@router.get("/orders/{order_id}", response_model=OrderDto)
def get_order(
    order_id: str,
    session: Session = Depends(get_session),
    user: User = Depends(get_current_user),
):
    order = session.get(Order, order_id)
    if order is None or order.user_id != user.id:
        raise HTTPException(status_code=404, detail="order not found")
    return _load_order_dto(session, order)


CANCELABLE = {"CREATED", "ASSIGNED", "RESERVED", "BOARDING"}


@router.post("/orders/{order_id}/board", response_model=OrderDto)
def board(
    order_id: str,
    session: Session = Depends(get_session),
    user: User = Depends(get_current_user),
):
    order = session.get(Order, order_id)
    if order is None or order.user_id != user.id:
        raise HTTPException(status_code=404, detail="order not found")
    if order.status != "BOARDING":
        raise HTTPException(status_code=409, detail="order not in BOARDING")
    vehicle = session.get(Vehicle, order.vehicle_id)
    now = now_millis()
    order.status = "IN_FLIGHT"
    order.updated_at = now
    if vehicle is not None:
        vehicle.status = "IN_FLIGHT"
        vehicle.current_vertiport_id = None
        vehicle.updated_at = now
    session.commit()
    session.refresh(order)
    return _load_order_dto(session, order)


@router.post("/orders/{order_id}/cancel", response_model=OrderDto)
def cancel(
    order_id: str,
    session: Session = Depends(get_session),
    user: User = Depends(get_current_user),
):
    order = session.get(Order, order_id)
    if order is None or order.user_id != user.id:
        raise HTTPException(status_code=404, detail="order not found")
    if order.status not in CANCELABLE:
        raise HTTPException(status_code=409, detail="order not cancelable")
    vehicle = session.get(Vehicle, order.vehicle_id)
    now = now_millis()

    pickup_vp = session.get(Vertiport, order.pickup_vertiport_id)
    pickup_route_km = haversine_km(
        order.vehicle_origin_lat, order.vehicle_origin_lng,
        pickup_vp.latitude, pickup_vp.longitude,
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
        vehicle.current_vertiport_id = None
        vehicle.updated_at = now
    session.commit()
    session.refresh(order)
    return _load_order_dto(session, order)
