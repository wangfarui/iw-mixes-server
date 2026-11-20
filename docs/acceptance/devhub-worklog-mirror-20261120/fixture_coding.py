"""Local CODING simulator for HTTP/browser acceptance. Synthetic identities only.

Run with python3 fixture_coding.py. Listens on loopback:19406.
POST /control accepts {"failPersonal": bool, "extra": bool, "delay": seconds}.
GET /stats reports counters without authorization headers or credential values.
"""
from collections import Counter
from datetime import datetime, timedelta
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import threading
import time
from zoneinfo import ZoneInfo

LOCK = threading.Lock()
STATE = {"failPersonal": False, "extra": False, "delay": 0}
BASE_TIME = int(time.time() * 1000)
COUNTERS = Counter()
ZONE = ZoneInfo("Asia/Shanghai")


def worklogs(user, manager=False):
    now = int(time.time() * 1000)
    today = datetime.now(ZONE).replace(hour=12, minute=0, second=0, microsecond=0)
    start = int((today - timedelta(days=1)).timestamp() * 1000)
    count = 12 if user == 101 else 1
    if STATE["extra"] and user == 101:
        count += 1
    result = []
    for i in range(count):
        code = user // 10
        result.append({"Id": user * 100 + i, "UserId": user, "IssueId": 1000 + code,
                       "IssueCode": code, "ProjectName": "fixture-project", "RecordHours": 0.5 if user == 101 else 2,
                       "WorkingDesc": "合成验收记录", "StartAt": start + i * 60000,
                       "CreatedAt": STATE.get("extraAt", now) if i == 12 else BASE_TIME - 3600000 + i * 1000,
                       "UpdatedAt": STATE.get("extraAt", now) if i == 12 else BASE_TIME - 3500000 + i * 1000})
    if manager and user == 101:
        result.append({**result[0], "Id": 99999, "ProjectName": "manager-only-project", "RecordHours": 7})
    return result


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def send(self, value, status=200):
        body = json.dumps(value, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json;charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == "/stats":
            with LOCK:
                self.send({"counts": dict(COUNTERS), "state": STATE.copy()})
        else:
            self.send({"error": "unknown"}, 404)

    def do_POST(self):
        request = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))) or b"{}")
        if self.path == "/control":
            with LOCK:
                if request.get("extra") and not STATE["extra"]:
                    STATE["extraAt"] = int(time.time() * 1000)
                STATE.update({k: v for k, v in request.items() if k in STATE})
            return self.send({"ok": True})
        action = request.get("Action", "")
        auth = self.headers.get("Authorization", "")
        if auth.rsplit(" ", 1)[-1] not in {"synthetic-a", "synthetic-b", "synthetic-manager"}:
            return self.send({"Response": {"Error": {"Code": "Unauthorized", "Message": "Only synthetic fixture credentials are accepted"}}})
        user = 202 if auth.endswith("synthetic-b") else 101
        manager = auth.endswith("synthetic-manager")
        with LOCK:
            COUNTERS[action] += 1
            if action == "DescribeAllProjectsIssueWorkLogList":
                COUNTERS[f"worklog:{'manager' if manager else 'personal'}:{request.get('UserId')}"] += 1
        response = {"RequestId": "synthetic-request"}
        if action == "DescribeCodingCurrentUser":
            response["User"] = {"Id": user, "TeamId": 10, "Name": f"验收成员{user}", "Avatar": "", "GlobalKey": f"fixture-{user}"}
        elif action == "DescribeTeam":
            response["Team"] = {"Id": 10, "Name": "fixture", "GlobalKey": "fixture", "TeamHost": "https://fixture.invalid"}
        elif action == "DescribeAllProjectsIssueWorkLogList":
            time.sleep(float(STATE["delay"]))
            if STATE["failPersonal"] and not manager:
                response["Error"] = {"Code": "Forbidden", "Message": "synthetic permission denied"}
            else:
                rows = worklogs(int(request["UserId"]), manager)
                rows = [r for r in rows if int(request["StartAt"]) <= r["CreatedAt"] <= int(request["EndAt"])]
                offset, limit = int(request.get("Offset", 0)), int(request.get("Limit", 1000))
                response["WorkLogs"] = rows[offset:offset+limit]
        elif action == "DescribeIssue":
            code = int(request["IssueCode"])
            response["Issue"] = {"Id": 1000+code, "Code": code, "Name": f"工时镜像验收事项 {code}",
                                 "Type": "SUB_TASK", "IssueTypeDetail": {"Name": "子工作项"},
                                 "ProjectName": request["ProjectName"], "Project": {"DisplayName": "镜像验收项目"}}
        elif action == "DescribeUserProjects":
            response["Projects"] = [{"Id": 1, "Name": "fixture-project", "DisplayName": "镜像验收团队"}]
        elif action == "DescribeProjectMembers":
            response["Data"] = {"ProjectMembers": [{"User": {"Id": n, "TeamId": 10, "Name": f"验收成员{n}", "Avatar": ""}} for n in (101, 202, 303)], "TotalCount": 3}
        elif action == "DescribeMyDepots":
            response.update({"Depots": [], "TotalCount": 0})
        elif action == "DescribeCodingCIJobs":
            response["JobList"] = []
        elif action == "DescribeIssueWorkLogList":
            response["WorkLogs"] = []
        else:
            response["Error"] = {"Code": "UnsupportedSyntheticAction", "Message": "unsupported fixture action"}
        self.send({"Response": response})


if __name__ == "__main__":
    ThreadingHTTPServer(("127.0.0.1", 19406), Handler).serve_forever()
