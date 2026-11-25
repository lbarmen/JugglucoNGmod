#!/usr/bin/env python3
"""JugglucoNGmod: their JVM test suites in MOD sync, retried when the red is not a red test (mod-sync.yml, tests).

A red attempt is sorted out by its log and its JUnit XML:
  network  a download failed: Maven answered 429, a plugin or a dependency was not resolved, Robolectric could not
           fetch its android-all image (then every test of that SDK fails, often more than a thousand). The whole
           command again, at most 3 attempts, 60 s and 120 s apart.
  flaky    at most FLAKY_MAX test classes failed, none of ours (package mod.), no download sign: only those classes
           once more (`--tests` on their task). Green on that retry -> green, with a "passed on retry" row.
  ours     one of our classes (mod.*) failed for a reason other than a download: red at once, never retried.
  many     more than FLAKY_MAX classes failed: red, no retry.
  build    the command failed with no failed test and no download sign (a compile error, a crash): red.
After a class retry the XML of the first run is put back with only the retried classes replaced, so out/junit.txt
counts every class once, in its final state, our classes included. Rows for out/tests.md go to --notes; --deps-state
gets `complete` when every test task ran with all its downloads (the job then saves the dependency cache under its
main key), otherwise `partial`.

  python3 -u mod/jvm-tests.py --results DIR --notes FILE [--deps-state FILE] -- ./gradlew :task ... --flags
  python3 mod/jvm-tests.py --classify LOG [--results DIR]     verdict for one saved attempt (its log and XML)
  python3 mod/jvm-tests.py --self-test
"""
import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET
from pathlib import Path

FLAKY_MAX = 5
PAUSES = (60, 120)
OURS = 'mod.'
ECHO = True  # the self-test keeps the fake gradle quiet
# in Gradle's "What went wrong" blocks (or the whole log when there is none: the wrapper download fails before Gradle)
LOG_NET = re.compile('|'.join([
    r'status code 429', r'response code: 429', r'Too Many Requests',
    r'status code 50[234]', r'response code: 50[234]',
    r'Could not resolve all (?:files|dependencies|artifacts)', r'could not resolve plugin artifact',
    r'Could not (?:GET|HEAD|download|get resource)',
    r'was not found in any of the following sources',
    r'MavenArtifactFetcher', r'Failed to fetch maven artifact',
    r'Read timed out', r'connect timed out', r'Connection reset',
    r'(?:UnknownHost|SocketTimeout|NoRouteToHost)Exception', r'Remote host terminated the handshake',
]), re.I)
# in a failed test: only Robolectric's own download (a test of network code may fail with "Connection reset" itself)
XML_NET = re.compile(r'Failed to fetch maven artifact|MavenArtifactFetcher')
WRONG = re.compile(r'^\* What went wrong:\n(.*?)(?=^\* Try:|^\* Exception is:|\Z)', re.S | re.M)


def say(text):
    if ECHO:
        print(text, flush=True)


def log_signs(log):
    blocks = WRONG.findall(log)
    seen = []
    for m in LOG_NET.finditer('\n'.join(blocks) if blocks else log):
        s = m.group(0)
        if s.lower() not in (x.lower() for x in seen):
            seen.append(s)
    return seen


def failed_classes(results):
    """[{task, name, path, net}] for every TEST-*.xml with a failure or an error; net = all its failures are downloads."""
    out = []
    for f in sorted(Path(results).glob('*/TEST-*.xml')):
        try:
            r = ET.parse(f).getroot()
        except ET.ParseError:
            out.append(dict(task=f.parent.name, name=f.name[5:-4], path=str(f), net=False))
            continue
        bad = [e for tc in r.iter('testcase') for e in tc if e.tag in ('failure', 'error')]
        if not bad and int(r.get('failures') or 0) + int(r.get('errors') or 0) == 0:
            continue
        net = bool(bad) and all(XML_NET.search((e.get('message') or '') + (e.text or '')) for e in bad)
        out.append(dict(task=f.parent.name, name=r.get('name') or f.name[5:-4], path=str(f), net=net))
    return out


def classify(log, results):
    failed = failed_classes(results) if results else []
    real = [c for c in failed if not c['net']]
    ours = [c for c in real if c['name'].startswith(OURS)]
    signs = log_signs(log)
    nnet = sum(c['net'] for c in failed)
    if nnet:
        signs.append(f'android-all not fetched in {nnet} classes')
    if ours:
        kind = 'ours'
    elif signs:
        kind = 'network'
    elif not failed:
        kind = 'build'
    elif len(real) <= FLAKY_MAX:
        kind = 'flaky'
    else:
        kind = 'many'
    return kind, dict(failed=failed, real=real, ours=ours, signs=signs)


def names(cs, limit=8):
    n = [c['name'] + (f" ({c['task']})" if c.get('task') else '') for c in cs]
    return ', '.join(n[:limit]) + (f' and {len(n) - limit} more' if len(n) > limit else '')


def execute(cmd, title):
    say(f'===== MOD jvm-tests: {title}: {" ".join(cmd)}')
    p = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    chunks = []
    for line in p.stdout:
        if ECHO:
            sys.stdout.buffer.write(line)
        chunks.append(line)
    sys.stdout.flush()
    rc = p.wait()
    say(f'===== MOD jvm-tests: {title}: exit {rc}')
    return rc, b''.join(chunks).decode('utf-8', 'replace')


def passed_xml(path):
    try:
        r = ET.parse(path).getroot()
    except (ET.ParseError, OSError):
        return False
    return int(r.get('tests') or 0) > 0 and int(r.get('failures') or 0) + int(r.get('errors') or 0) == 0


def retry_classes(cmd, results, real):
    """Runs the failed classes again on their tasks; puts the first run's XML back with these classes replaced."""
    first = next((i for i, a in enumerate(cmd) if i and a.startswith(':')), len(cmd))
    prefix, tasks = cmd[:first], [a for a in cmd[first:] if a.startswith(':')]
    flags = [a for a in cmd[first:] if a.startswith('-')]
    groups = {}
    for c in real:
        task = next((t for t in tasks if t.rsplit(':', 1)[-1] == c['task']), None)
        if task is None:
            return False, f"no task in the command for the results folder {c['task']}"
        groups.setdefault(task, []).append(c['name'])
    again = list(prefix)
    for task, cls in groups.items():
        again.append(task)
        for n in cls:
            again += ['--tests', n]
    again += flags
    snap = Path(tempfile.mkdtemp(prefix='mod-jvm-')) / 'r'
    shutil.copytree(results, snap)
    try:
        rc, _ = execute(again, 'retry of the failed classes')
        fresh = {c['path']: Path(c['path']).read_bytes() for c in real if Path(c['path']).is_file()}
        ok = rc == 0 and all(passed_xml(c['path']) for c in real)
        shutil.rmtree(results, ignore_errors=True)
        shutil.copytree(snap, results)
        for p, b in fresh.items():
            Path(p).write_bytes(b)
    finally:
        shutil.rmtree(snap.parent, ignore_errors=True)
    return ok, ''


def run(cmd, results, notes, deps_state, pauses=PAUSES):
    rows, most = [], len(pauses) + 1
    tasks = [a for a in cmd[1:] if a.startswith(':')]
    kind, info, rc = 'build', {}, 1
    for attempt in range(1, most + 1):
        shutil.rmtree(results, ignore_errors=True)
        s = time.time()
        rc, log = execute(cmd, f'attempt {attempt} of {most}')
        dt = round(time.time() - s)
        if rc == 0:
            kind = 'green'
            break
        kind, info = classify(log, results)
        say(f'MOD jvm-tests: attempt {attempt}: {kind}; download signs: {info["signs"] or "none"}; '
            f'failed classes: {len(info["failed"])}')
        if kind == 'network' and attempt < most:
            pause = pauses[attempt - 1]
            rows.append(f'| JVM: attempt {attempt} of {most} failed on a download ({", ".join(info["signs"][:3])}), '
                        f'the whole command again in {pause} s | retried | {dt} s |')
            time.sleep(pause)
            continue
        break
    ran_all = all(any((Path(results) / t.rsplit(':', 1)[-1]).glob('TEST-*.xml')) for t in tasks)
    complete = ran_all and kind not in ('network', 'build')
    ok = kind == 'green'
    if kind == 'flaky':
        s = time.time()
        ok, why = retry_classes(cmd, results, info['real'])
        dt = round(time.time() - s)
        if ok:
            rows.append(f'| JVM: passed on retry: {names(info["real"])} | green | {dt} s |')
        else:
            rows.append(f'| JVM: failed again on retry: {names(info["real"])}{" - " + why if why else ""} '
                        f'| **red** | {dt} s |')
    elif kind == 'network':
        rows.append(f'| JVM: download failure on all {most} attempts ({", ".join(info["signs"][:3])}) | **red** | |')
    elif kind == 'ours':
        rows.append(f'| JVM: our tests red, never retried: {names(info["ours"])} | **red** | |')
    elif kind == 'many':
        rows.append(f'| JVM: {len(info["real"])} classes red, too many for a retry (at most {FLAKY_MAX}): '
                    f'{names(info["real"], 5)} | **red** | |')
    elif kind == 'build':
        rows.append('| JVM: failed with no failed test and no download sign (see the log) | **red** | |')
    if notes:
        Path(notes).parent.mkdir(parents=True, exist_ok=True)
        Path(notes).write_text(''.join(r + '\n' for r in rows), encoding='utf-8')
    if deps_state:
        Path(deps_state).parent.mkdir(parents=True, exist_ok=True)
        Path(deps_state).write_text('complete\n' if complete else 'partial\n', encoding='utf-8')
    say(f'MOD jvm-tests: {"green" if ok else "red"} ({kind}); dependencies {"complete" if complete else "partial"}')
    return 0 if ok else (rc or 1)


# ---------- self-test: classification on lines from real red attempts (Nov 2026), and the whole flow on a fake gradle

def _suite(name, fails=(), tests=3):
    cases = ''.join(f'<testcase name="t{i}" classname="{name}"><failure message="{m}">{m}\n\tat x.Y(Y.java:1)'
                    f'</failure></testcase>' for i, m in enumerate(fails))
    cases += ''.join(f'<testcase name="ok{i}" classname="{name}"/>' for i in range(tests - len(fails)))
    return (f'<?xml version="1.0" encoding="UTF-8"?><testsuite name="{name}" tests="{tests}" skipped="0" '
            f'failures="{len(fails)}" errors="0">{cases}</testsuite>')


NET_FAIL = ('java.lang.AssertionError: Failed to fetch maven artifact org.robolectric:android-all-instrumented:'
            '14-robolectric-10818077-i7&#10;\tat org.robolectric.internal.dependency.MavenArtifactFetcher.fetchArtifact'
            '(MavenArtifactFetcher.java:129)&#10;Caused by: java.io.IOException: Server returned HTTP response code: '
            '429 for URL: https://repo1.maven.org/maven2/org/robolectric/android-all-instrumented/x.jar')
FLAKY_FAIL = ("java.lang.AssertionError: Action performScrollTo() failed.&#10;Reason: Expected exactly '1' node but "
              "could not find any node that satisfies: (Text contains 'Meal rice')")
OUR_FAIL = 'java.lang.AssertionError: expected:&lt;3&gt; but was:&lt;4&gt;'
SOCKET_FAIL = 'java.net.SocketException: Connection reset'
LOG_TESTS = ("FoodPickerActionsTests > pickRice FAILED\n    java.lang.AssertionError at ComposeUiTest.kt:12\n\n"
             "FAILURE: Build failed with an exception.\n\n* What went wrong:\nExecution failed for task "
             "':Common:testMobileDebugUnitTest'.\n> There were failing tests. See the report at: file:///x/index.html"
             "\n\n* Try:\n> Run with --scan.\n\nBUILD FAILED in 7m 55s\n")
LOG_MASS = ("CloneCurrentGlucoseProductionRouteTests > takingOver FAILED\n    java.lang.AssertionError at "
            "MavenArtifactFetcher.java:129\n        Caused by: java.io.IOException at HttpURLConnection.java:2034\n"
            + LOG_TESTS)
LOG_429 = ("1: Task failed with an exception.\n-----------\n* What went wrong:\nExecution failed for task "
           "':Common:testWearDebugUnitTest' (registered by plugin 'com.android.internal.application').\n> Could not "
           "resolve all files for configuration ':Common:wearDebugUnitTestRuntimeClasspath'.\n   > Could not download "
           "nativeruntime-dist-compat-1.0.16.jar (org.robolectric:nativeruntime-dist-compat:1.0.16)\n      > Could "
           "not get resource 'https://repo.maven.apache.org/maven2/org/robolectric/x.jar'.\n         > Could not GET "
           "'https://repo.maven.apache.org/maven2/org/robolectric/x.jar'.\n            > Received status code 429 "
           "from server: Too Many Requests\n\n* Try:\n> Run with --info.\n\nBUILD FAILED in 5m 22s\n")
LOG_PLUGIN = ("FAILURE: Build failed with an exception.\n\n* Where:\nBuild file '/w/build.gradle' line: 3\n\n"
              "* What went wrong:\nPlugin [id: 'com.android.application', version: '9.3.2', apply: false] was not "
              "found in any of the following sources:\n\n- Gradle Core Plugins (plugin is not in 'org.gradle' "
              "namespace)\n- Plugin Repositories (could not resolve plugin artifact 'com.android.application:"
              "com.android.application.gradle.plugin:9.3.2')\n\n* Try:\n> Run with --info.\n\nBUILD FAILED in 12s\n")
LOG_COMPILE = ("e: file:///w/Common/src/test/java/tk/X.kt:12:5 Unresolved reference 'bar'.\n\n* What went wrong:\n"
               "Execution failed for task ':Common:compileMobileDebugUnitTestKotlin'.\n> Compilation error. See log "
               "for more details\n\n* Try:\n> Run with --info.\n\nBUILD FAILED in 3m\n")
MOB, WEAR = 'testMobileDebugUnitTest', 'testWearDebugUnitTest'


def _write(results, suites):
    for (task, name), xml in suites.items():
        d = Path(results) / task
        d.mkdir(parents=True, exist_ok=True)
        (d / f'TEST-{name}.xml').write_text(xml, encoding='utf-8')


FAKE = r'''
import json, os, shutil, sys
from pathlib import Path
d = Path(os.environ['FAKE_DIR']); n = int((d / 'n').read_text()) if (d / 'n').exists() else 0
(d / 'n').write_text(str(n + 1))
with open(d / 'calls', 'a') as f: f.write(json.dumps(sys.argv[1:]) + '\n')
step = json.loads((d / 'scenario.json').read_text())[n]
res = Path(os.environ['FAKE_RESULTS'])
for task in {k.split('/')[0] for k in step['xml']}: shutil.rmtree(res / task, ignore_errors=True)
for k, v in step['xml'].items():
    (res / k).parent.mkdir(parents=True, exist_ok=True); (res / k).write_text(v)
print(step['log']); sys.exit(step['rc'])
'''


def _flow(tmp, scenario):
    d = Path(tempfile.mkdtemp(dir=tmp))
    (d / 'fake.py').write_text(FAKE, encoding='utf-8')
    (d / 'scenario.json').write_text(json.dumps(scenario), encoding='utf-8')
    res = d / 'results'
    os.environ.update(FAKE_DIR=str(d), FAKE_RESULTS=str(res))
    cmd = [sys.executable, str(d / 'fake.py'), ':Common:' + MOB, ':Common:' + WEAR, '--continue', '--no-daemon']
    rc = run(cmd, str(res), str(d / 'notes.md'), str(d / 'deps.txt'), pauses=(0, 0))
    calls = [json.loads(x) for x in (d / 'calls').read_text().splitlines()]
    totals = [0, 0]
    for f in res.glob('*/TEST-*.xml'):
        r = ET.parse(f).getroot()
        totals[0] += int(r.get('tests'))
        totals[1] += int(r.get('failures')) + int(r.get('errors'))
    return rc, calls, (d / 'notes.md').read_text(), (d / 'deps.txt').read_text().strip(), totals


def self_test():
    global ECHO
    ECHO, bad = False, []

    def check(what, got, want):
        if got != want:
            bad.append(f'{what}: got {got!r}, want {want!r}')

    tmp = tempfile.mkdtemp(prefix='mod-jvm-selftest-')
    try:
        k = lambda log, suites: (lambda r: (_write(r, suites), classify(log, r)[0])[1])(tempfile.mkdtemp(dir=tmp))
        check('mass android-all failure, ours among them', k(LOG_MASS, {
            (MOB, 'mod.widget.ModWidgetRenderTest'): _suite('mod.widget.ModWidgetRenderTest', [NET_FAIL] * 3),
            (WEAR, 'tk.glucodata.CloneTests'): _suite('tk.glucodata.CloneTests', [NET_FAIL] * 2),
            (MOB, 'tk.glucodata.Fine'): _suite('tk.glucodata.Fine')}), 'network')
        check('429 on the test classpath, no XML', k(LOG_429, {}), 'network')
        check('plugin not found, no XML', k(LOG_PLUGIN, {}), 'network')
        check('one flaky class of theirs', k(LOG_TESTS, {
            (MOB, 'tk.glucodata.ui.journal.FoodPickerActionsTests'): _suite('tk.glucodata.ui.journal.FoodPickerActionsTests', [FLAKY_FAIL]),
            (MOB, 'mod.watch.OngoingAlertTest'): _suite('mod.watch.OngoingAlertTest')}), 'flaky')
        check('two flaky classes of theirs', k(LOG_TESTS, {
            (MOB, 'tk.glucodata.drivers.ottai.OttaiFirmwareConnectTests'): _suite('tk.glucodata.drivers.ottai.OttaiFirmwareConnectTests', ['java.lang.AssertionError']),
            (MOB, 'tk.glucodata.ui.AiDexKeyManagementTests'): _suite('tk.glucodata.ui.AiDexKeyManagementTests', [FLAKY_FAIL])}), 'flaky')
        check('their class failing with a socket error of its own', k(LOG_TESTS, {
            (MOB, 'tk.glucodata.NightscoutTests'): _suite('tk.glucodata.NightscoutTests', [SOCKET_FAIL])}), 'flaky')
        check('our test red', k(LOG_TESTS, {
            (WEAR, 'mod.watch.SnoozeButtonTest'): _suite('mod.watch.SnoozeButtonTest', [OUR_FAIL])}), 'ours')
        check('our test red inside a mass download failure', k(LOG_MASS, {
            (WEAR, 'mod.watch.SnoozeButtonTest'): _suite('mod.watch.SnoozeButtonTest', [OUR_FAIL]),
            (WEAR, 'tk.glucodata.CloneTests'): _suite('tk.glucodata.CloneTests', [NET_FAIL])}), 'ours')
        check('six classes of theirs', k(LOG_TESTS, {
            (MOB, f'tk.glucodata.T{i}'): _suite(f'tk.glucodata.T{i}', [FLAKY_FAIL]) for i in range(6)}), 'many')
        check('compile error', k(LOG_COMPILE, {}), 'build')
        check('flaky class while the other task hit 429', k(LOG_429, {
            (MOB, 'tk.glucodata.ui.AiDexKeyManagementTests'): _suite('tk.glucodata.ui.AiDexKeyManagementTests', [FLAKY_FAIL])}), 'network')

        ok_m = {f'{MOB}/TEST-tk.A.xml': _suite('tk.A'), f'{MOB}/TEST-mod.Ours.xml': _suite('mod.Ours', tests=4)}
        ok_w = {f'{WEAR}/TEST-tk.B.xml': _suite('tk.B', tests=2)}
        green = dict(rc=0, log='BUILD SUCCESSFUL', xml={**ok_m, **ok_w})
        rc, calls, notes, deps, tot = _flow(tmp, [green])
        check('flow green: exit, calls, rows, deps', (rc, len(calls), notes, deps), (0, 1, '', 'complete'))

        net = dict(rc=1, log=LOG_429, xml={})
        rc, calls, notes, deps, tot = _flow(tmp, [net, dict(net, log=LOG_PLUGIN), green])
        check('flow network twice then green: exit, calls, deps', (rc, len(calls), deps), (0, 3, 'complete'))
        check('flow network twice then green: retried rows', notes.count('| retried |'), 2)
        check('flow network twice then green: totals', tot, [9, 0])

        rc, calls, notes, deps, tot = _flow(tmp, [net, net, net])
        check('flow network 3 times: exit, calls, deps', (rc != 0, len(calls), deps), (True, 3, 'partial'))
        check('flow network 3 times: red row', 'download failure on all 3 attempts' in notes, True)

        flaky = dict(rc=1, log=LOG_TESTS, xml={**ok_m, **ok_w, f'{WEAR}/TEST-tk.B.xml': _suite('tk.B', [FLAKY_FAIL], 2)})
        rc, calls, notes, deps, tot = _flow(tmp, [flaky, dict(rc=0, log='ok', xml={f'{WEAR}/TEST-tk.B.xml': _suite('tk.B', tests=2)})])
        check('flow flaky then green: exit, deps', (rc, deps), (0, 'complete'))
        check('flow flaky: retry command', calls[1],[':Common:' + WEAR, '--tests', 'tk.B', '--continue', '--no-daemon'])
        check('flow flaky: row', notes.strip(), f'| JVM: passed on retry: tk.B ({WEAR}) | green | 0 s |')
        check('flow flaky: final totals keep every class, ours too', tot, [9, 0])

        rc, calls, notes, deps, tot = _flow(tmp, [flaky, dict(flaky, xml={f'{WEAR}/TEST-tk.B.xml': _suite('tk.B', [FLAKY_FAIL], 2)})])
        check('flow flaky twice: exit, calls', (rc != 0, len(calls)), (True, 2))
        check('flow flaky twice: row and totals', ('failed again on retry: tk.B' in notes, tot), (True, [9, 1]))

        ours = dict(rc=1, log=LOG_TESTS, xml={**ok_w, f'{MOB}/TEST-mod.Ours.xml': _suite('mod.Ours', [OUR_FAIL], 4)})
        rc, calls, notes, deps, tot = _flow(tmp, [ours])
        check('flow ours red: exit, calls, row', (rc != 0, len(calls), 'our tests red, never retried: mod.Ours' in notes),
              (True, 1, True))
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
    for b in bad:
        print('FAIL  self-test:', b)
    if not bad:
        print('OK    self-test: jvm-tests classification and retries')
    return 1 if bad else 0


def main():
    ap = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    ap.add_argument('--self-test', action='store_true')
    ap.add_argument('--classify', metavar='LOG')
    ap.add_argument('--results', help='folder of JUnit XML: <task>/TEST-*.xml')
    ap.add_argument('--notes', help='rows for out/tests.md')
    ap.add_argument('--deps-state', help='gets "complete" or "partial"')
    ap.add_argument('cmd', nargs=argparse.REMAINDER)
    a = ap.parse_args()
    if a.self_test:
        return self_test()
    if a.classify:
        kind, info = classify(Path(a.classify).read_text(encoding='utf-8', errors='replace'), a.results)
        print(f'{kind}; download signs: {", ".join(info["signs"]) or "none"}; failed classes: {len(info["failed"])} '
              f'(download {sum(c["net"] for c in info["failed"])}, other {len(info["real"])}: {names(info["real"], 5)})')
        return 0
    cmd = a.cmd[1:] if a.cmd[:1] == ['--'] else a.cmd
    if not cmd or not a.results:
        ap.error('give --results and the command after --')
    return run(cmd, a.results, a.notes, a.deps_state)


if __name__ == '__main__':
    sys.exit(main())
