"""Synthetic CODING API for the isolated shared-statistics acceptance run."""
from collections import Counter
from datetime import datetime, timedelta
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from zoneinfo import ZoneInfo
import json
import threading
import time

ZONE = ZoneInfo("Asia/Shanghai")
NOW = datetime.now(ZONE)
TODAY = NOW.replace(hour=0, minute=0, second=0, microsecond=0)
LAST_MONTH = TODAY.replace(day=1) - timedelta(days=2)
LOCK = threading.Lock()
COUNTS = Counter()
REQUESTS = []
STATE = {"advance": False, "failDiff": False, "failWorklog": False, "delay": 0}
ACCOUNTS = {
    "synthetic-a": (101, (91,)),
    "synthetic-b": (202, (91,)),
    "synthetic-b-wide": (202, (91, 92, 93)),
    "synthetic-mgr": (101, (91, 92)),
    "synthetic-mgr-same": (101, (91, 92)),
    "synthetic-mgr-narrow": (101, (91,)),
}
NAMES = {91: "fixture-project", 92: "fixture-private", 93: "fixture-added"}
ORG = 910003


def millis(value):
    return int(value.timestamp() * 1000)


def commits(depot):
    suffix = depot - 90
    first = {"Sha": str(suffix) * 40, "AuthorName": "Synthetic 202", "AuthorEmail": "b@example.invalid",
             "CommitDate": millis(LAST_MONTH), "ShortMessage": f"历史提交 {depot}", "Parents": []}
    second = {"Sha": hex(suffix + 2)[2:] * 40, "AuthorName": "Synthetic 202",
              "AuthorEmail": "b@example.invalid", "CommitDate": millis(TODAY - timedelta(days=1)),
              "ShortMessage": f"本月提交 {depot}", "Parents": [first["Sha"]]}
    third = {**second, "Sha": hex(suffix + 4)[2:] * 40, "CommitDate": millis(TODAY - timedelta(hours=1)),
             "ShortMessage": f"新增提交 {depot}", "Parents": [second["Sha"]]}
    return [third, second, first] if STATE["advance"] else [second, first]


def worklogs(user, depots):
    rows = []
    for depot in depots:
        for index, day, hours in ((1, LAST_MONTH, 3), (2, TODAY - timedelta(days=1), 4)):
            rows.append({"Id": user * 1000 + depot * 10 + index, "UserId": user, "IssueId": depot,
                         "IssueCode": depot, "ProjectName": NAMES[depot], "RecordHours": hours,
                         "WorkingDesc": f"合成工时 {depot}", "StartAt": millis(day),
                         "CreatedAt": millis(day + timedelta(hours=1)), "UpdatedAt": millis(day+timedelta(hours=1))})
    return rows


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def send(self, payload):
        body = json.dumps(payload, ensure_ascii=False).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        with LOCK:
            self.send({"counts": dict(COUNTS), "requests": REQUESTS.copy()})

    def do_POST(self):
        data = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))) or b"{}")
        if self.path == "/control":
            with LOCK:
                STATE.update({k: v for k, v in data.items() if k in STATE})
            return self.send({"ok": True})
        credential = self.headers.get("Authorization", "").split()[-1:]
        if not credential or credential[0] not in ACCOUNTS:
            return self.send({"Response": {"Error": {"Code": "Unauthorized", "Message": "Synthetic fixture only"}}})
        account = credential[0]
        user, depots = ACCOUNTS[account]
        action = data.get("Action")
        with LOCK:
            COUNTS[action] += 1
            if action in ("DescribeAllProjectsIssueWorkLogList", "DescribeGitCommitsInPage", "DescribeGitCommitDiff"):
                REQUESTS.append({k: data[k] for k in ("Action", "UserId", "StartAt", "EndAt", "DepotId", "Ref", "Sha") if k in data})
        result = {"RequestId": "synthetic-shared"}
        if action == "DescribeCodingCurrentUser":
            result["User"] = {"Id": user, "Name": f"Synthetic {user}", "TeamId": ORG, "Avatar": "",
                              "Email": "a@example.invalid" if user == 101 else "b@example.invalid"}
        elif action == "DescribeTeam":
            result["Team"] = {"Id": ORG, "Name": "fixture", "GlobalKey": "fixture", "TeamHost": "https://fixture.invalid"}
        elif action == "DescribeUserProjects":
            result["Projects"] = [{"Id": dep, "Name": NAMES[dep], "DisplayName": NAMES[dep]} for dep in depots]
        elif action == "DescribeProjectMembers":
            result["Data"] = {"TotalCount": 2, "ProjectMembers": [
                {"User": {"Id": uid, "TeamId": ORG, "Name": f"Synthetic {uid}"}} for uid in (101, 202)]}
        elif action == "DescribeMyDepots":
            result["Depots"] = [{"Id": dep, "Name": NAMES[dep], "ProjectId": dep,
                                 "ProjectName": NAMES[dep], "DepotType": "CODING", "VcsType": "git"} for dep in depots]
            result["TotalCount"] = len(depots)
        elif action == "DescribeProjectDepotBranches":
            depot = int(data.get("Id", 0))
            if depot not in depots:
                result["Error"] = {"Code": "Forbidden", "Message": "Synthetic depot denied"}
            else:
                head = commits(depot)[0]
                result["Branches"] = [{"Name": name, "Sha": head["Sha"], "LastCommitDate": head["CommitDate"]}
                                      for name in ("main", "release")]
        elif action == "DescribeGitCommitsInPage":
            depot = int(data["DepotId"])
            if depot not in depots:
                result["Error"] = {"Code": "Forbidden", "Message": "Synthetic depot denied"}
            else:
                time.sleep(float(STATE["delay"]))
                items = commits(depot)
                ref = data.get("Ref", "")
                index = next((i for i, row in enumerate(items) if row["Sha"] == ref), 0)
                result["Commits"] = items[index:]
                result["TotalCount"] = len(result["Commits"])
        elif action == "DescribeGitCommitDiff":
            depot = int(data["DepotId"])
            if depot not in depots:
                result["Error"] = {"Code": "Forbidden", "Message": "Synthetic diff denied"}
            elif depot == 92 and STATE["failDiff"]:
                result["Error"] = {"Code": "FailedOperation", "Message": "Synthetic diff temporarily unavailable"}
            else:
                result["Diffs"] = [{"Path": "Synthetic.java", "Additions": 12, "Deletions": 3}]
        elif action == "DescribeAllProjectsIssueWorkLogList":
            time.sleep(float(STATE["delay"]))
            if STATE["failWorklog"]:
                result["Error"] = {"Code": "ServiceUnavailable", "Message": "Synthetic worklog outage"}
            else:
                result["WorkLogs"] = [row for row in worklogs(int(data["UserId"]), depots)
                                      if data["StartAt"] <= row["CreatedAt"] <= data["EndAt"]]
        elif action == "DescribeIssue":
            issue = int(data["IssueCode"])
            result["Issue"] = {"Id": issue, "Code": issue, "Name": "合成事项", "Type": "TASK",
                               "ProjectName": data["ProjectName"]}
        elif action == "DescribeIssueWorkLogList":
            result["WorkLogs"] = []
        else:
            result["Error"] = {"Code": "UnsupportedSyntheticAction", "Message": "Synthetic fixture has no such action"}
        self.send({"Response": result})


if __name__ == "__main__":
    ThreadingHTTPServer(("127.0.0.1", 19487), Handler).serve_forever()
