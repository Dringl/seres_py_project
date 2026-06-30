# 本地运行速查

```bash
# 本地开发
cd server
python -m pip install -r requirements.txt
copy .env.example .env   # Windows；Linux: cp .env.example .env
python -m uvicorn app.main:app --reload --port 8000

# 跑测试
python -m pytest -v

# Docker
docker compose up --build
```
