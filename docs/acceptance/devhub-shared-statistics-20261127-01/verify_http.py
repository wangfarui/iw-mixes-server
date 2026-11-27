"""Exercise the actual isolated backend with the synthetic CODING server."""
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta
from http.cookiejar import CookieJar
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import Request, build_opener, HTTPCookieProcessor, urlopen
from zoneinfo import ZoneInfo
import json
import time
import uuid

BASE = 'http://127.0.0.1:18487/external-service/api/zhaogang'
FIXTURE = 'http://127.0.0.1:19487'
TODAY = datetime.now(ZoneInfo('Asia/Shanghai')).date()
FROM = TODAY.replace(day=1) - timedelta(days=3)
ACQUISITION = ('DescribeAllProjectsIssueWorkLogList', 'DescribeGitCommitsInPage', 'DescribeGitCommitDiff')


class Client:
    def __init__(self, token):
        self.opener = build_opener(HTTPCookieProcessor(CookieJar()))
        self.call('/session/bind', method='POST', headers={'Authorization': f'token {token}'})

    def call(self, path, body=None, method=None, headers=None):
        request = Request(BASE+path, data=None if body is None else json.dumps(body).encode(),
                          method=method, headers={'Content-Type': 'application/json', **(headers or {})})
        with self.opener.open(request, timeout=35) as response:
            result = json.load(response)
        if result['code'] != 200:
            raise AssertionError((path, result.get('message')))
        return result['data']


def fixture(path='/stats', data=None):
    req = Request(FIXTURE+path, data=None if data is None else json.dumps(data).encode(),
                  headers={'Content-Type': 'application/json'})
    with urlopen(req, timeout=10) as response:
        return json.load(response)


def counters():
    counts = fixture()['counts']
    return {action: counts.get(action, 0) for action in ACQUISITION}


def wait(check, accept, name, seconds=80):
    until = time.monotonic() + seconds
    while time.monotonic() < until:
        last = check()
        if accept(last):
            return last
        time.sleep(0.4)
    raise AssertionError((name, last))


def query(team=None, **extra):
    return '?' + urlencode({'from': FROM, 'to': TODAY - timedelta(days=1),
                            'scope': 'SELF' if team is None else 'WORKBENCH_TEAM',
                            **({} if team is None else {'workbenchTeamId': team}), **extra})


def work(client, team=None, user=None):
    return client.call('/worklogs/entries/page' + query(team, **({} if user is None else {'memberUserId': user})))['entries']


def sync_work(client, team=None):
    return client.call('/worklogs/sync' + query(team), method='POST')


def wait_work(client, team=None, complete=True):
    return wait(lambda: client.call('/worklogs/sync' + query(team)),
                lambda state: not state['running'] and (not complete or not state['partial']), 'worklog sync')


def sync_code(client, team=None):
    return client.call('/code-stats/scan', {'scope': 'SELF' if team is None else 'WORKBENCH_TEAM',
                                           'workbenchTeamId': team, 'from': str(FROM),
                                           'to': str(TODAY), 'mode': 'SYNC'})


def wait_code(client, team=None):
    return wait(lambda: client.call('/code-stats/scan' + query(team)),
                lambda state: state['sync']['status'] in ('COMPLETED','PARTIAL','FAILED'), 'code sync')


def repos(client, team=None, user=None):
    return {row['depotId'] for row in client.call('/code-stats/entries' + query(
        team, **({} if user is None else {'memberUserId': user})))['items']}


def rotate(client, team, token):
    current = client.call(f'/workbench-teams/{team}')
    return client.call(f'/workbench-teams/{team}/management-token',
                       {'versionNo': current['versionNo'], 'token': token}, 'PUT')


def set_role(client, team, user, role):
    current = client.call(f'/workbench-teams/{team}')
    return client.call(f'/workbench-teams/{team}/members/{user}/coding-role',
                       {'versionNo': current['versionNo'], 'codingRole': role}, 'PUT')


def ignore(client, team, user, enabled):
    current = client.call(f'/workbench-teams/{team}')
    return client.call(f'/workbench-teams/{team}/members/{user}/team-statistics',
                       {'versionNo': current['versionNo'], 'ignoreTeamStatistics': enabled}, 'PUT')


def member_values(report, user=202):
    return next(member['values'] for member in report['members']['items'] if str(member['userId']) == str(user))


def report(client, **extra):
    return client.call('/statistics/report?' + urlencode({'from': FROM, 'to': TODAY - timedelta(days=1),
                       'rangeKind':'CUSTOM', 'compare':'false', **extra}))


def result(client):
    return wait(lambda: report(client), lambda item: item['hasData'] and item['refresh']['state'] == 'IDLE', 'report')


def verify_recovery(admin, expanded, team, other_team, failed_job):
    fixture('/control', {'failDiff': False})
    before_diff_retry = counters()
    failures = expanded.call(f'/code-stats/scan/{failed_job}/failures')['diffFailures']
    assert len(failures) == 1 and failures[0]['depotId'] == 92
    retry = expanded.call(f'/code-stats/scan/{failed_job}/diff-retry',
                          {'targets': [{'depotId': 92, 'sha': failures[0]['sha']}]})
    wait(lambda: expanded.call(f"/code-stats/diff-retry/{retry['jobId']}"),
         lambda job: job['status'] == 'COMPLETED', 'targeted diff retry')
    assert counters()[ACQUISITION[1]] == before_diff_retry[ACQUISITION[1]]
    assert counters()[ACQUISITION[2]] - before_diff_retry[ACQUISITION[2]] == 1
    assert all(row['additions'] is not None for row in expanded.call('/code-stats/entries' + query())['items'])

    fixture('/control', {'failWorklog': True})
    last_sync = expanded.call('/worklogs/sync' + query())['syncedAt']
    last_hours = work(expanded)['totalHours']
    sync_work(expanded)
    failed = wait_work(expanded, complete=False)
    assert failed['partial'] and failed['syncedAt'] == last_sync and work(expanded)['totalHours'] == last_hours
    fixture('/control', {'failWorklog': False})
    sync_work(expanded)
    wait_work(expanded)
    assert work(expanded)['totalHours'] == 21

    # The role applies across teams. An ignore flag excludes the member only after all memberships are ignored.
    set_role(admin, team, 202, 'PRODUCT')
    assert result(admin)['population']['developers'] == 0
    set_role(admin, team, 202, 'BACKEND')
    ignore(admin, team, 202, True)
    assert result(admin)['population']['participating'] == 2
    ignore(admin, other_team, 202, True)
    assert result(admin)['population']['participating'] == 1
    ignore(admin, team, 202, False)
    ignore(admin, other_team, 202, False)
    zero = admin.call('/code-stats/statistics' + query())
    assert zero['dataReady'] and zero['summary']['commitCount'] == 0


def main():
    admin = Client('synthetic-a')
    created = admin.call('/workbench-teams', {'name': 'fixture-project', 'requestId': str(uuid.uuid4())})
    team = int(created['id'])
    updated = rotate(admin, team, 'synthetic-mgr')
    admin.call(f'/workbench-teams/{team}/members', {'versionNo': updated['versionNo'], 'projectId': 91,
                                                       'matchTeamName': False, 'userIds': [202]})
    set_role(admin, team, 202, 'BACKEND')
    sync_work(admin, team)
    first_work = wait_work(admin, team)
    assert work(admin, team, 202)['totalHours'] == 14
    assert sync_code(admin, team)['accepted']
    first_code = wait_code(admin, team)
    assert first_code['sync']['status'] == 'COMPLETED', first_code
    assert repos(admin, team, 202) == {91, 92}
    first_report = result(admin)
    assert first_report['population']['developers'] == 1
    assert member_values(first_report)['TOTAL_HOURS'] == 14
    assert member_values(first_report)['COMMIT_COUNT'] == 4

    # Start a worklog round, then rotate to another credential with the same permission.
    time.sleep(10.2)
    fixture('/control', {'delay': .8})
    running = sync_work(admin, team)
    assert running['running']
    rotate(admin, team, 'synthetic-mgr-same')
    fixture('/control', {'delay': 0})
    wait(lambda: work(admin, team, 202)['totalHours'], lambda hours: hours == 14, 'same-scope rotation visibility')
    assert admin.call('/worklogs/sync' + query(team))['syncedAt'] == first_work['syncedAt']
    before = counters()
    assert sync_code(admin, team)['accepted']
    wait_code(admin, team)
    assert counters()[ACQUISITION[1]] == before[ACQUISITION[1]], 'Unchanged repository heads fetched commit pages'

    # Narrow manager permissions. Personal grant and team grant have independent sets.
    rotate(admin, team, 'synthetic-mgr-narrow')
    wait(lambda: work(admin, team, 202)['totalHours'], lambda hours: hours == 7, 'narrow worklog')
    wait(lambda: repos(admin, team, 202), lambda items: items == {91}, 'narrow code')
    after_narrow = result(admin)
    assert after_narrow['snapshotId'] != first_report['snapshotId']
    assert member_values(after_narrow)['TOTAL_HOURS'] == 7
    assert member_values(after_narrow)['COMMIT_COUNT'] == 2
    old_page = report(admin, snapshotId=first_report['snapshotId'], pageNumber=2)
    assert old_page['snapshotId'] == after_narrow['snapshotId'] and old_page['members']['page']['pageNumber'] == 1

    # Binding + invitation use the member's stable key. The new grant shows overlapping history
    # without running worklog collection or a commit scan.
    before_bind = counters()
    member = Client('synthetic-b')
    member.call(f"/workbench-team-invitations/{created['inviteCode']}/join", method='POST')
    wait(lambda: work(member)['totalHours'], lambda hours: hours == 7, 'self worklog history')
    wait(lambda: repos(member), lambda items: items == {91}, 'self code history')
    assert counters() == before_bind

    # Use a second Dev Hub team with the same CODING member. Both teams see the same SHA once.
    other = admin.call('/workbench-teams', {'name': 'Shared Fixture B', 'requestId': str(uuid.uuid4())})
    member.call(f"/workbench-team-invitations/{other['inviteCode']}/join", method='POST')
    assert repos(admin, int(other['id']), 202) == {91}
    two_team_report = result(admin)
    assert two_team_report['population']['participating'] == 2

    # One new SHA from two overlapping jobs. Existing head + parent graph is sufficient to stop.
    fixture('/control', {'advance': True, 'delay': .25})
    before_concurrent = counters()
    with ThreadPoolExecutor(2) as pool:
        triggers = [pool.submit(sync_code, member), pool.submit(sync_code, admin, team)]
        assert all(job.result()['accepted'] for job in triggers)
    wait_code(member)
    wait_code(admin, team)
    fixture('/control', {'delay': 0})
    after_concurrent = counters()
    assert after_concurrent[ACQUISITION[1]] - before_concurrent[ACQUISITION[1]] == 1
    assert after_concurrent[ACQUISITION[2]] - before_concurrent[ACQUISITION[2]] == 1
    result(admin)

    # Refresh/pagination are local even with the same snapshot visible in parallel.
    before_refresh = counters()
    previous = report(admin)
    request = report(admin, refresh='true')
    refreshed = result(admin)
    assert previous['refresh']['generatedAt'] != refreshed['refresh']['generatedAt']
    assert previous['dataStatus'] == refreshed['dataStatus']
    assert counters() == before_refresh
    paged = report(admin, pageNumber=2, pageSize=1, sortMetric='COMMIT_COUNT', sortOrder='ASC', keyword='Synthetic')
    assert paged['members']['page']['pageNumber'] == 2 and len(paged['members']['items']) == 1
    assert counters() == before_refresh

    # A wider personal credential reveals previous history and leaves the new repository uncovered until sync.
    before_expand = counters()
    expanded = Client('synthetic-b-wide')
    for path in ('/worklogs/entries/page' + query(),
                 '/code-stats/entries' + query(),
                 '/statistics/report?rangeKind=THIS_MONTH'):
        try:
            member.call(path)
        except (AssertionError, HTTPError) as rejected:
            description = rejected.read().decode() if isinstance(rejected, HTTPError) else str(rejected)
            assert 'CODING 凭证已变更' in description, rejected
        else:
            raise AssertionError(f'Previous session still authorized for {path}')
    wait(lambda: work(expanded)['totalHours'], lambda hours: hours == 14, 'expansion existing history')
    wait(lambda: repos(expanded), lambda items: items == {91, 92}, 'expansion existing repositories')
    assert counters() == before_expand
    fixture('/control', {'failDiff': True})
    start_log = len(fixture()['requests'])
    assert sync_code(expanded)['accepted']
    partial_code = wait_code(expanded)
    assert partial_code['sync']['status'] == 'PARTIAL', partial_code
    calls = fixture()['requests'][start_log:]
    assert {request['DepotId'] for request in calls if request['Action'] == ACQUISITION[1]} == {92,93}
    assert repos(expanded) == {91,92,93}
    assert {row['depotId'] for row in expanded.call('/code-stats/entries' + query())['items']
            if row['additions'] is None} == {92}
    verify_recovery(admin, expanded, team, int(other['id']), partial_code['sync']['jobId'])

    print(json.dumps({'teamId': team, 'secondTeamId': other['id'], 'worklogTotalBeforeNarrow': 14,
                      'worklogTotalAfterNarrow': 7, 'codeRepositoriesBeforeNarrow': [91, 92],
                      'codeRepositoriesAfterNarrow': [91], 'remoteOnPersonalBind': 0,
                      'overlappingNewCommitPages': after_concurrent[ACQUISITION[1]] - before_concurrent[ACQUISITION[1]],
                      'overlappingNewDiffCalls': after_concurrent[ACQUISITION[2]] - before_concurrent[ACQUISITION[2]],
                      'refreshRemoteAcquisition': 0, 'ongoingRefreshPreservedData': request['hasData'],
                      'organizationPopulation': refreshed['population'],
                      'newOrChangedDepotsOnExpansion': [92, 93],
                      'directedDiffRetryCalls': 1,
                      'worklogHoursAfterRetry': 21,
                      'failedWorklogPreservedHistoryAndTime': True,
                      'organizationRoleAndIgnoreChecks': True, 'fullyCoveredSelfZero': True}, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
