"""Exercise the actual isolated iw-external HTTP API against fixture_coding.py.

All account bindings and teams created here are synthetic and confined to
127.0.0.1:18006 / devhub_worklog_final. No production credentials are used.
"""
import http.cookiejar
import json
import time
import urllib.request
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo

BASE = "http://127.0.0.1:18006/external-service/api/zhaogang"
FIXTURE = "http://127.0.0.1:19406"
CLIENT = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))


def call(path, body=None, method=None, headers=None):
    request = urllib.request.Request(BASE+path, data=None if body is None else json.dumps(body).encode(),
                                     method=method, headers={"Content-Type": "application/json", **(headers or {})})
    with CLIENT.open(request, timeout=30) as result:
        payload = json.load(result)
    if payload["code"] != 200:
        raise AssertionError((path, payload.get("message")))
    return payload["data"]


def fixture(path, body=None):
    with urllib.request.urlopen(urllib.request.Request(FIXTURE+path, data=None if body is None else json.dumps(body).encode(),
                                                      headers={"Content-Type": "application/json"}), timeout=10) as result:
        return json.load(result)


def wait(scope="SELF", team=None):
    suffix = f"scope={scope}"+(f"&workbenchTeamId={team}" if team else "")
    for _ in range(100):
        state = call("/worklogs/sync?"+suffix)
        if not state["running"]:
            return state
        time.sleep(0.2)
    raise AssertionError("Sync did not finish")


def main():
    fixture("/control", {"failPersonal": False, "extra": False, "delay": 0})
    call("/session/bind", method="POST", headers={"Authorization": "token synthetic-a"})
    today = datetime.now(ZoneInfo("Asia/Shanghai")).date()
    start = today.replace(day=1)
    suffix = f"from={start}&to={today}&scope=SELF"
    initial = call("/worklogs/report?"+suffix)
    finished = wait()
    assert finished["syncedAt"] and not finished["partial"], finished
    report = call("/worklogs/report?"+suffix)
    page = call("/worklogs/entries/page?"+suffix)
    assert len(page["entries"]["items"]) == 10 and page["nextCursor"]
    assert page["entries"]["totalHours"] == 6
    assert all("工时镜像验收事项" in row["issue"]["title"] for row in page["entries"]["items"])
    next_page = call("/worklogs/entries/page?"+suffix+"&cursor="+page["nextCursor"])
    assert len(next_page["entries"]["items"]) == 2 and next_page["nextCursor"] is None
    before = fixture("/stats")["counts"].get("DescribeAllProjectsIssueWorkLogList", 0)
    began = time.monotonic()
    for _ in range(10):
        call("/worklogs/report?"+suffix)
        call("/worklogs/entries/page?"+suffix)
    elapsed = time.monotonic()-began
    after = fixture("/stats")["counts"].get("DescribeAllProjectsIssueWorkLogList", 0)
    assert before == after, "Local queries called CODING"
    time.sleep(10.1)
    fixture("/control", {"extra": True, "delay": 0.5})
    triggered = call("/worklogs/sync?scope=SELF", method="POST")
    assert triggered["running"] and triggered["syncedAt"] == finished["syncedAt"]
    updated = wait()
    assert updated["syncedAt"] != finished["syncedAt"] and not updated["partial"]
    assert call("/worklogs/entries/page?"+suffix)["entries"]["totalHours"] == 6.5
    time.sleep(10.1)
    fixture("/control", {"failPersonal": True})
    call("/worklogs/sync?scope=SELF", method="POST")
    failed = wait()
    assert failed["partial"] and failed["syncedAt"] == updated["syncedAt"]
    assert failed["members"][0]["state"] == "FAILED"
    assert "不会自动切换" in failed["members"][0]["message"]
    fixture("/control", {"failPersonal": False, "delay": 0})
    result = {"initialPartial": initial["statistics"]["coverage"]["partial"], "firstPage": 10, "secondPage": 2,
              "localQueries": 20, "localQueriesSeconds": round(elapsed, 3), "extraRemoteWorklogRequests": after-before,
              "incrementTotalHours": 6.5, "timeAdvancedOnSuccess": True, "failurePreservedTime": True,
              "failureDidNotFallback": True}
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
