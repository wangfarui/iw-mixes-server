#!/usr/bin/env python3
"""Read five test credentials from a private pipe; pass tokens only to a child environment."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import urllib.request

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--execute', required=True, action='store_true')
p.add_argument('--node', required=True)
p.add_argument('--run-id', required=True)
p.add_argument('--month', required=True)
p.add_argument('--state-dir', required=True)
p.add_argument('--runner', default='run.mjs')
p.add_argument('--preflight-only', action='store_true')
p.add_argument('--base-url', choices=['http://127.0.0.1:18000', 'http://127.0.0.1:18001'], default='http://127.0.0.1:18000')
a = p.parse_args()
assert not sys.stdin.isatty(), 'Credentials require a private pipe'
credentials = json.loads(sys.stdin.readline())
environment = os.environ.copy()
base = a.base_url
def request(path, token=None, body=None):
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['iwtoken'] = token
    req = urllib.request.Request(base + path, headers=headers,
        data=None if body is None else json.dumps(body).encode())
    with urllib.request.urlopen(req, timeout=20) as response:
        result = json.load(response)
    assert result.get('code') == 200, 'Local authentication/preflight failed'
    return result.get('data')
try:
    for role in 'ABCDE':
        user = request('/auth-service/login/password', body=credentials.pop(role))
        token = user['tokenValue']
        environment['IW_ACCEPTANCE_TOKEN_' + role] = token
        group = request('/auth-service/family/group/myGroup', token)
        print(json.dumps({'role': role, 'userId': user['id'],
            'familyId': group.get('id') if group else None,
            'actualRole': group.get('currentUserRole') if group else None},
            ensure_ascii=False), flush=True)
        del user, token, group
    if not a.preflight_only:
        runner = Path(__file__).resolve().parent / a.runner
        assert runner.parent == Path(__file__).resolve().parent and runner.is_file()
        result = subprocess.run([a.node, str(runner), '--execute', '--base-url', base,
            '--environment', 'local', '--run-id', a.run_id, '--month', a.month,
            '--dedicated-test-accounts', '--state-dir', a.state_dir], env=environment)
        sys.exit(result.returncode)
except Exception as error:
    # Do not serialize authentication replies, credentials, headers or remote error bodies.
    print(json.dumps({'status': 'BLOCKED', 'detail': type(error).__name__}), flush=True)
    sys.exit(1)
finally:
    credentials.clear()
    for role in 'ABCDE':
        environment.pop('IW_ACCEPTANCE_TOKEN_' + role, None)
