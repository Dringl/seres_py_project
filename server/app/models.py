from sqlalchemy import Boolean, Float, ForeignKey, Integer, String
from sqlalchemy.orm import Mapped, mapped_column, relationship

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
    # UoW 依赖：确保插入时 Vertiport 先于 Vehicle
    current_vertiport = relationship("Vertiport", foreign_keys=[current_vertiport_id])


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
    # UoW 依赖：父表先插
    pickup_vertiport = relationship("Vertiport", foreign_keys=[pickup_vertiport_id])
    destination = relationship("Vertiport", foreign_keys=[destination_id])
    vehicle = relationship("Vehicle", foreign_keys=[vehicle_id])


class AdminUser(Base):
    __tablename__ = "admin_users"
    username: Mapped[str] = mapped_column(String, primary_key=True)
    password_hash: Mapped[str] = mapped_column(String)
    created_at: Mapped[int] = mapped_column(Integer, default=0)
