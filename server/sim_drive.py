"""시연용 주행 시뮬레이터 — 에뮬레이터 내비가 ETA를 못 줄 때 대시보드 데모를 돌린다.

차 한 대가 매장까지 30분 거리에서 주문하고, 조리 시작 전에 정체로 6분 늦어졌다가 2분 회복하는 시나리오.
--speed 로 시간을 압축한다(기본 30배: 34분 → 68초).

실행: ../.venv/bin/python sim_drive.py --store s1 --speed 30
"""
import argparse
import json
import time
import urllib.request

ap = argparse.ArgumentParser()
ap.add_argument("--base", default="http://127.0.0.1:8080")
ap.add_argument("--store", default="s1")
ap.add_argument("--plate", default="12가 3456")
ap.add_argument("--speed", type=float, default=30.0)
args = ap.parse_args()


def post(path, body=None):
    req = urllib.request.Request(args.base + path, method="POST",
                                 data=json.dumps(body or {}).encode(),
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req) as r:
        return json.load(r)


def get(path):
    with urllib.request.urlopen(args.base + path) as r:
        return json.load(r)


menu = get("/stores")
store = next(s for s in menu if s["id"] == args.store)

# 실제 남은 시간(초)과, 정체로 생기는 추가 지연 계획
remain = 30 * 60
delay_plan = {4 * 60: +6 * 60, 14 * 60: -2 * 60}  # 조리 시작 전에 정체 +6분, 일부 해소 -2분

order = post("/orders", {"storeId": store["id"], "itemIds": [store["menu"][0]["id"]],
                         "carPlate": args.plate, "remainTimeSec": int(remain / args.speed)})
oid = order["id"]
print(f"주문 {oid}: {store['name']} / 도착까지 {remain // 60}분 (시뮬 {args.speed:g}배속)")

# 남은 시간은 벽시계 기준으로 계산한다. 배속을 걸면 sleep·요청 지연이 쌓여 도착 예정이 밀리기 때문.
trip = remain
start = time.time()
step = 30  # 실제 시간 30초마다 ETA 갱신
while remain > 0:
    time.sleep(step / args.speed)
    elapsed = (time.time() - start) * args.speed
    for at, delta in list(delay_plan.items()):
        if elapsed >= at:
            trip += delta
            print(f"  경과 {int(elapsed // 60)}분: 교통 변화 {delta // 60:+d}분 → 남은 {int((trip - elapsed) // 60)}분")
            del delay_plan[at]
    remain = trip - elapsed
    o = post(f"/orders/{oid}/eta", {"remainTimeSec": round(max(remain, 0) / args.speed)})
print("  도착", post(f"/orders/{oid}/arrived")["status"])
