"""OnTime Pickup 목업 서버.

차량 앱이 내비 남은 시간(remainTime)을 올리면, 매장별 조리 시간에 맞춰
'조리 시작 시각'을 다시 계산한다. 매장 대시보드(/dashboard/{store_id})가 이를 폴링한다.

실행: ../.venv/bin/uvicorn app:app --host 0.0.0.0 --port 8080
"""
import os
import time
import uuid
from pathlib import Path

from fastapi import FastAPI, HTTPException
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel

HERE = Path(__file__).parent
app = FastAPI(title="OnTime Pickup mock server")
app.mount("/static", StaticFiles(directory=HERE / "static"), name="static")

# 시연 영상용 시간 압축 배율. sim_drive.py --speed 와 같은 값을 준다(예: TIME_SCALE=30).
TIME_SCALE = float(os.environ.get("TIME_SCALE", "1"))
# 조리 완료 후 손님이 도착하기까지 허용하는 여유(초). 음식이 식지 않을 만큼만 둔다.
READY_BUFFER_SEC = 60 / TIME_SCALE


def prep_sec(store: dict) -> float:
    return store["prepMin"] * 60 / TIME_SCALE

# 대전 유성구 일대 데모 매장. 좌표는 데모용 근사값.
# s0은 Pleos Connect Emulator 내비의 기본 위치(성남 수정구) 근처라, 에뮬레이터에서 실제 내비 ETA로 시연할 때 쓴다.
def _m(id, name, price, group, desc="", popular=False):
    return {"id": id, "name": name, "price": price, "group": group, "desc": desc, "popular": popular}


def _chicken(p):
    # 같은 브랜드 지점은 같은 메뉴판. 기존 id(m0/m01, m1/m2)는 즐겨찾기·음성 주문 호환을 위해 유지.
    first, half = {"s0": ("m0", "m01"), "s1": ("m1", "m2")}[p]
    return [
        _m(first, "후라이드 한 마리", 20000, "치킨", "겉은 바삭, 속은 촉촉한 기본 후라이드", True),
        _m(half, "양념 반 + 후라이드 반", 21000, "치킨", "고민될 땐 반반", True),
        _m(f"{p}c3", "양념치킨", 21000, "치킨", "달콤 매콤 수제 양념"),
        _m(f"{p}c4", "간장마늘치킨", 22000, "치킨", "통마늘 간장 소스"),
        _m(f"{p}c5", "순살 후라이드", 22000, "치킨", "뼈 없이 먹기 편한 순살"),
        _m(f"{p}c6", "치즈볼 5개", 5000, "사이드", "쭉 늘어나는 모짜렐라"),
        _m(f"{p}c7", "감자튀김", 4000, "사이드"),
        _m(f"{p}c8", "콜라 1.25L", 2500, "음료"),
    ]


# prepMin~prepMax: 매장이 안내하는 조리 시간 범위. 조리 시작 시각 계산에는 prepMin(평균에 가까운 값)을 쓴다.
STORES = {
    "s0": {"id": "s0", "name": "판교 바삭치킨", "category": "치킨", "prepMin": 12, "prepMax": 22, "rating": 4.8, "reviews": 1204,
           "lat": 37.3947, "lng": 127.1112, "address": "경기 성남시 분당구 판교",
           "menu": _chicken("s0")},
    "s1": {"id": "s1", "name": "궁동 바삭치킨", "category": "치킨", "prepMin": 18, "prepMax": 32, "rating": 4.7, "reviews": 836,
           "lat": 36.3626, "lng": 127.3519, "address": "대전 유성구 궁동",
           "menu": _chicken("s1")},
    "s2": {"id": "s2", "name": "어은동 화덕피자", "category": "피자", "prepMin": 15, "prepMax": 28, "rating": 4.9, "reviews": 512,
           "lat": 36.3667, "lng": 127.3586, "address": "대전 유성구 어은동",
           "menu": [_m("m3", "마르게리타 L", 18000, "피자", "토마토 · 모짜렐라 · 바질", True),
                    _m("m4", "고르곤졸라 L", 19000, "피자", "꿀을 곁들인 고르곤졸라", True),
                    _m("s2p3", "페퍼로니 L", 19000, "피자", "화덕에 구운 페퍼로니"),
                    _m("s2p4", "콰트로 포르마지 L", 21000, "피자", "네 가지 치즈"),
                    _m("s2p5", "트러플 감자튀김", 7000, "사이드"),
                    _m("s2p6", "시저 샐러드", 8000, "사이드"),
                    _m("s2p7", "콜라 500ml", 2000, "음료")]},
    "s3": {"id": "s3", "name": "봉명동 손짜장", "category": "중식", "prepMin": 12, "prepMax": 25, "rating": 4.6, "reviews": 1391,
           "lat": 36.3553, "lng": 127.3428, "address": "대전 유성구 봉명동",
           "menu": [_m("m5", "짜장면 2 + 탕수육 소", 27000, "세트", "2인 세트", True),
                    _m("s3n1", "짜장면", 7000, "면", "직접 뽑은 수타면", True),
                    _m("s3n2", "간짜장", 8500, "면", "주문 즉시 볶는 춘장"),
                    _m("s3n3", "짬뽕", 9000, "면", "불맛 가득 해물 짬뽕", True),
                    _m("s3n4", "차돌짬뽕", 11000, "면"),
                    _m("s3r1", "볶음밥", 8500, "밥", "짜장 소스 포함"),
                    _m("s3r2", "짬뽕밥", 9500, "밥"),
                    _m("s3r3", "잡채밥", 9500, "밥"),
                    _m("s3d1", "탕수육 소", 15000, "요리", "부먹 · 찍먹 소스 따로"),
                    _m("s3d2", "탕수육 중", 20000, "요리"),
                    _m("s3d3", "깐풍기", 22000, "요리"),
                    _m("s3s1", "군만두 6개", 6000, "사이드")]},
    "s4": {"id": "s4", "name": "유성 순대국밥", "category": "국밥", "prepMin": 12, "prepMax": 20, "rating": 4.8, "reviews": 977,
           "lat": 36.3589, "lng": 127.3371, "address": "대전 유성구 봉명동",
           "menu": [_m("s4g1", "순대국밥", 9000, "국밥", "진하게 우린 사골 육수", True),
                    _m("s4g2", "특 순대국밥", 11000, "국밥", "고기 · 순대 두 배"),
                    _m("s4g3", "머릿고기 국밥", 10000, "국밥"),
                    _m("m6", "순대국밥 포장 2", 18000, "세트", "2인분 포장", True),
                    _m("s4s1", "모둠순대 소", 12000, "사이드"),
                    _m("s4s2", "공기밥", 1000, "사이드")]},
}

ORDERS: dict[str, dict] = {}


class OrderIn(BaseModel):
    storeId: str
    itemIds: list[str]
    carPlate: str
    remainTimeSec: int  # 주문 시점에 내비가 알려준 매장(경유지)까지 남은 시간


class EtaIn(BaseModel):
    remainTimeSec: int


def _schedule(order: dict, remain_sec: int) -> None:
    """남은 시간이 바뀔 때마다 조리 시작 시각을 다시 잡는다."""
    now = time.time()
    store = STORES[order["storeId"]]
    order["arriveAt"] = now + remain_sec
    order["etaHistory"].append({"at": now, "remainSec": remain_sec})
    if order["status"] == "SCHEDULED":
        start = order["arriveAt"] - prep_sec(store) - READY_BUFFER_SEC
        order["cookStartAt"] = max(now, start)


def _advance(order: dict) -> dict:
    """시간 경과에 따른 상태 전이. 조리 시작·완료는 매장이 눌러도 되고 자동으로도 넘어간다."""
    now = time.time()
    store = STORES[order["storeId"]]
    if order["status"] == "SCHEDULED" and now >= order["cookStartAt"]:
        order["status"] = "COOKING"
        order["cookingSince"] = order["cookStartAt"]
    if order["status"] == "COOKING" and now >= order["cookingSince"] + prep_sec(store):
        order["status"] = "READY"
    return order


def _view(order: dict) -> dict:
    _advance(order)
    now = time.time()
    return {**order,
            "store": {k: STORES[order["storeId"]][k] for k in ("id", "name", "prepMin")},
            "prepSec": prep_sec(STORES[order["storeId"]]),
            "now": now,
            "secToCookStart": max(0, int(order["cookStartAt"] - now)),
            "secToArrive": max(0, int(order["arriveAt"] - now))}


@app.get("/stores")
def stores():
    return list(STORES.values())


@app.post("/orders")
def create_order(body: OrderIn):
    if body.storeId not in STORES:
        raise HTTPException(404, "store not found")
    menu = {m["id"]: m for m in STORES[body.storeId]["menu"]}
    items = [menu[i] for i in body.itemIds if i in menu]
    if not items:
        raise HTTPException(400, "no valid items")
    oid = uuid.uuid4().hex[:8]
    order = {"id": oid, "storeId": body.storeId, "items": items,
             "total": sum(i["price"] for i in items), "carPlate": body.carPlate,
             "status": "SCHEDULED", "createdAt": time.time(), "etaHistory": [],
             # 결제는 Pleos Payment SDK 오픈 전까지 목업. 사전 결제 완료로 간주한다.
             "payment": "MOCK_PREPAID"}
    _schedule(order, body.remainTimeSec)
    ORDERS[oid] = order
    return _view(order)


@app.post("/orders/{oid}/eta")
def update_eta(oid: str, body: EtaIn):
    order = ORDERS.get(oid) or _404()
    _schedule(order, body.remainTimeSec)
    return _view(order)


@app.post("/orders/{oid}/arrived")
def arrived(oid: str):
    order = ORDERS.get(oid) or _404()
    order["arrivedAt"] = time.time()
    order["carArrived"] = True
    return _view(order)


@app.post("/orders/{oid}/status/{status}")
def set_status(oid: str, status: str):
    """매장 대시보드 버튼: COOKING(지금 조리 시작) / READY / PICKED_UP / CANCELLED."""
    order = ORDERS.get(oid) or _404()
    if status not in {"COOKING", "READY", "PICKED_UP", "CANCELLED"}:
        raise HTTPException(400, "bad status")
    if status == "COOKING" and order["status"] == "SCHEDULED":
        order["cookingSince"] = time.time()
    order["status"] = status
    return _view(order)


@app.get("/orders/{oid}")
def get_order(oid: str):
    return _view(ORDERS.get(oid) or _404())


@app.get("/stores/{sid}/orders")
def store_orders(sid: str):
    return sorted((_view(o) for o in ORDERS.values() if o["storeId"] == sid),
                  key=lambda o: o["createdAt"], reverse=True)


@app.get("/dashboard/{sid}")
def dashboard(sid: str):
    return FileResponse(HERE / "static" / "dashboard.html")


@app.get("/")
def index():
    return {"stores": [f"/dashboard/{s}" for s in STORES], "docs": "/docs"}


def _404():
    raise HTTPException(404, "order not found")
