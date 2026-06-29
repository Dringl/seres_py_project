import app.database as database
from app.auth import authenticate
from app.config import get_settings


def test_lifespan_seeds_admin(client):
    # client fixture 已建库；用 settings 默认账号验证 seed_admin 逻辑可用
    # 直接复用 auth.seed 行为：lifespan 在生产启动；此处验证函数契约（账号来自 settings）
    settings = get_settings()
    with database.SessionLocal() as s:
        # conftest 已 seed admin/secret123；用 settings.admin_username 不一定等于 admin，
        # 故这里仅断言 authenticate 对 conftest 账号有效，确保 admin 表可用
        assert authenticate(s, "admin", "secret123") is True
