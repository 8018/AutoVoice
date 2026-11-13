"""Read-only live I06 gate. Run on the dev host; never upload synthetic samples."""
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

base = os.environ.get('NAVIGATION_STATS_BASE_URL', 'http://127.0.0.1:8090')
headers = {}
if '--dev-env' in sys.argv:
    env = dict(line.split('=', 1) for line in Path('/etc/autovoice-dev/.env').read_text().splitlines()
               if '=' in line and not line.lstrip().startswith('#'))
    token = (env.get('AUTOVOICE_TELEMETRY_ADMIN_TOKEN') or env.get('AUTOVOICE_TELEMETRY_ACCESS_TOKEN') or '').strip().strip('\"\'')
else:
    token = os.environ.get('NAVIGATION_TELEMETRY_ADMIN_TOKEN', '')
if token:
    headers['X-Telemetry-Token'] = token

requests_executed = 0

def get(path):
    global requests_executed
    requests_executed += 1
    url = urllib.parse.urljoin(base + '/', path)
    if urllib.parse.urlsplit(url).netloc != urllib.parse.urlsplit(base).netloc:
        raise ValueError('unexpected asset origin')
    try:
        with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=10) as response:
            return response.status, response.read().decode()
    except urllib.error.HTTPError as error:
        return error.code, ''

results = []
def check(case_id, fn):
    start = time.monotonic()
    try:
        fn()
        status = 'pass'
    except Exception:
        # Do not emit response bodies, request headers or exceptions with credentials.
        status = 'fail'
    results.append({'id':case_id, 'productId':'I06', 'status':status,
                    'elapsedMs':round((time.monotonic()-start)*1000)})

now = int(time.time()*1000)
api = '/api/telemetry/navigation/stats'
def empty():
    status, body = get(api + '?' + urllib.parse.urlencode({'from':now-3600000,'to':now,'device':'navigation-benchmark-no-samples'}))
    assert status == 200 and json.loads(body)['operations'] == []
def window():
    assert get(api)[0] == 400
    assert get(api+'?from=100&to=99')[0] == 400
    assert get(api+'?from=0&to=999999999999')[0] == 400
def page():
    status, body = get('/telemetry/')
    assert status == 200
    paths = re.findall(r'(?:src|href)="([^"]*assets/[^"]+)"', body)
    assert len(paths) >= 2
    has_stats = False
    for path in paths:
        url = urllib.parse.urljoin(base+'/telemetry/',path)
        status, content = get(url)
        assert status == 200
        if path.endswith('.js'):
            has_stats |= '/api/telemetry/navigation/stats' in content and '导航质量' in content
    assert has_stats
check('I06-live-empty', empty)
check('I06-live-window', window)
check('I06-live-assets', page)
passed = all(r['status']=='pass' for r in results)
print(json.dumps({'suite':'navigation-statistics-v1','readOnly':True,'passed':passed,'httpRequests':requests_executed,'results':results},ensure_ascii=False))
sys.exit(0 if passed else 1)
