#!/usr/bin/env python3
"""JugglucoNGmod: publish a candidate for everyone - the very files the owner checked on the phone.

Owner's decision 25.11.2026: a GitHub release only after the owner's "all ok" on the phone for this very candidate, and
no rebuild. The files come from the owner's copy ../apk/mod-X.Y.Z-N (mod/apk-keep.py fetch downloaded and checked it)
and must equal GitHub's own record of the candidate run: the sha256 lines its build job printed. GitHub keeps job logs
90 days (repository setting, the maximum for a public repository) and the artifact only 14, so the log is the record.

  python mod/publish-candidate.py mod-X.Y.Z-N           checks only, nothing is written (default)
  python mod/publish-candidate.py mod-X.Y.Z-N --probe   the checks, then the whole upload into a draft under a probe
                                                        tag, GitHub's digests checked, the draft deleted (a draft is
                                                        seen only with write access; the in-app updater skips drafts)
  python mod/publish-candidate.py mod-X.Y.Z-N --publish --checked "TEXT"
                                                        the checks; a draft with the 3 files; their digests; the
                                                        annotated tag; the release made normal and Latest; checked
                                                        again, also as an anonymous visitor
  python mod/publish-candidate.py mod-X.Y.Z-N --verify  read only: a published release = the kept copy

--checked (required with --publish) records the owner's "all ok": what was checked, where, when - one line of plain
English, nothing personal; it becomes the third line of the public tag message. The script cannot hear the owner:
it is run with --publish only after the owner's "all ok" for this candidate, never after "not ok".
--publish refuses, and nothing goes out, when: the candidate is not the one marked installed (apk-keep.py installed);
it has block branches (its code is not one commit of main); its commit is not in main; a release of this tag exists
(a draft too); a published release of a newer version exists (the in-app updater takes the newest by publication
date); the tag points elsewhere; the run is not a successful manual candidate run of mod-build.yml on main; a hash or
the signature differs anywhere.
Needs: gh (signed in, write access), git (the tag is pushed from this repository; its committer e-mail must be a
GitHub noreply address), Android build-tools and Java as for mod/apk-keep.py.
Exit: 0 OK; 1 a check failed or a step could not be done, nothing published; 2 published, but something after it
failed or is unknown (look at the release page, then --verify); 64 wrong arguments.
"""
import argparse
import hashlib
import importlib.util
import json
import os
import re
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
_spec = importlib.util.spec_from_file_location('apk_keep', HERE / 'apk-keep.py')
keep = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(keep)

REPO = keep.REPO
SCRIPT = 'mod/publish-candidate.py'
WORKFLOW_PATH = '.github/workflows/mod-build.yml'
BUILD_JOB = 'mod candidate build with our key'  # start of the job name in mod-build.yml
PROBE_PREFIX = 'probe-publish-'
REL_TAG_RE = re.compile(r'^mod-(\d+)\.(\d+)\.(\d+)-(\d+)$')
RUN_URL_RE = re.compile(r'^https://github\.com/' + re.escape(REPO) + r'/actions/runs/(\d+)$')
# a sha256sum line inside a job log: "<job>\t<step>\t2026-11-24T12:15:41.6693528Z <hash>  <file>"
LOG_HASH_RE = re.compile(r'\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d+)?Z ([0-9a-f]{64})  (\S+)\s*$')
NOREPLY = '@users.noreply.github.com'
UA = 'JugglucoNGmod-publish-check'

if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8')


class Stop(Exception):
    """A step could not be done: nothing more is tried."""


class Checks:
    """kind 'files': the files or the run are not what they must be (nothing is uploaded, not even a probe);
    kind 'gate': only the real publication refuses (a probe goes on; with --verify a note)."""

    def __init__(self, gates=True):
        self.items = []
        self.gates = gates

    def ok(self, msg):
        say(f'OK    {msg}')

    def bad(self, kind, msg):
        if kind == 'gate' and not self.gates:
            say(f'note  {msg}')
            return
        self.items.append((kind, msg))
        say(f'{"FAIL" if kind == "files" else "STOP"}  {msg}')

    def of(self, kind):
        return [m for k, m in self.items if k == kind]


def say(msg=''):
    print(msg, flush=True)


def sh(cmd, check=True, inp=None, timeout=300):
    try:
        p = subprocess.run(cmd, input=inp, capture_output=True, text=True, encoding='utf-8', errors='replace',
                           timeout=timeout)
    except subprocess.TimeoutExpired:
        raise Stop(f'{" ".join(cmd)[:160]}: no answer in {timeout} s')
    except OSError as e:
        raise Stop(f'{cmd[0]}: {e}')
    if check and p.returncode != 0:
        raise Stop(f'{" ".join(cmd)[:160]}: exit {p.returncode}: {(p.stderr or p.stdout).strip()[:600]}')
    return p


def api(path, method='GET', body=None, missing_ok=False):
    """gh api as JSON; None for HTTP 404 when missing_ok."""
    cmd = ['gh', 'api', '-X', method, path, '-H', 'Accept: application/vnd.github+json']
    if body is not None:
        cmd += ['--input', '-']
    p = sh(cmd, check=False, inp=None if body is None else json.dumps(body))
    if p.returncode != 0:
        if missing_ok and 'HTTP 404' in p.stderr:
            return None
        raise Stop(f'GitHub API {method} {path}: {p.stderr.strip()[:400]}')
    out = p.stdout.strip()
    return json.loads(out) if out else {}


def releases():
    """Every release of the repository, drafts too (they need write access to be listed)."""
    out = sh(['gh', 'api', '--paginate', f'repos/{REPO}/releases?per_page=100', '--jq', '.[] | tojson']).stdout
    return [json.loads(line) for line in out.splitlines() if line.strip()]


def latest_tag():
    r = api(f'repos/{REPO}/releases/latest', missing_ok=True)
    return r['tag_name'] if r else None


def anon(url, accept='application/vnd.github+json'):
    """GET as an anonymous visitor (no token), as the in-app updater does."""
    req = urllib.request.Request(url, headers={'User-Agent': UA, 'Accept': accept})
    try:
        with urllib.request.urlopen(req, timeout=120) as r:
            return r.read()
    except (urllib.error.URLError, OSError) as e:
        raise Stop(f'anonymous GET {url}: {e}')


def remote_tag(tag):
    """Commit the tag points to on GitHub, or None when there is no such tag."""
    ref = api(f'repos/{REPO}/git/ref/tags/{tag}', missing_ok=True)
    if ref is None:
        return None
    obj = ref['object']
    for _ in range(5):  # annotated: tag object -> its object (a tag of a tag is followed too)
        if obj['type'] != 'tag':
            break
        obj = api(f'repos/{REPO}/git/tags/{obj["sha"]}')['object']
    return obj['sha'] if obj['type'] == 'commit' else f'{obj["type"]}:{obj["sha"]}'


def git(*args, check=True):
    return sh(['git', '-C', str(ROOT), *args], check=check)


# ---------- the kept copy ----------
def read_candidate_txt(path):
    info, hashes = {}, {}
    for line in path.read_text(encoding='utf-8', errors='replace').splitlines():
        m = re.match(r'^([0-9a-f]{64})\s+\*?(\S+)$', line.strip())
        if m:
            if m.group(2) != 'candidate.txt':  # hashed while tee was still writing it
                hashes[m.group(2)] = m.group(1)
            continue
        m = re.match(r'^(main|blocks|built|run|cert):\s+(.*)$', line)
        if m:
            info[m.group(1)] = m.group(2).strip()
        elif line.startswith('candidate '):
            info['candidate'] = line[len('candidate '):].strip()
    return info, hashes


def badging(apk):
    out = keep.run([keep.build_tool('aapt2'), 'dump', 'badging', str(apk)])
    m = re.search(r"package: name='([^']*)' versionCode='(\d+)' versionName='([^']*)'", out)
    if not m:
        raise Stop(f'aapt2 badging of {apk.name}: no package line')
    return m.group(1), int(m.group(2)), m.group(3)


def check_local(folder, version, n, c):
    """Checks on the kept copy; returns what the next steps need, or None when they cannot go on."""
    dist = folder / 'dist'
    phone = dist / f'JugglucoNGmod-{version}-{n}.apk'
    wear = dist / f'JugglucoNGmod-{version}-{n}-wear.apk'
    manifest = dist / 'update-manifest.json'
    notes = folder / 'release-notes.md'
    cand = dist / 'candidate.txt'
    record = folder / 'record.txt'
    if not folder.is_dir():
        c.bad('files', f'no kept copy {folder} (mod/apk-keep.py fetch makes it)')
        return None
    missing = [p.relative_to(folder).as_posix() for p in (phone, wear, manifest, notes, cand, record) if not p.is_file()]
    if missing:
        c.bad('files', f'kept copy {folder.name}: missing {", ".join(missing)}')
        return None
    files = (phone, wear, manifest)
    hashes = {p.name: keep.sha256(p) for p in files}
    sizes = {p.name: p.stat().st_size for p in files}

    info, listed = read_candidate_txt(cand)
    ours = keep.norm_hex((ROOT / 'mod' / 'mod-cert.sha256').read_text())
    if info.get('candidate') != f'{version}-{n}':
        c.bad('files', f'candidate.txt is of {info.get("candidate")!r}, not {version}-{n}')
    m = RUN_URL_RE.match(info.get('run', ''))
    main = info.get('main', '')
    if not m or not re.fullmatch(r'[0-9a-f]{40}', main):
        c.bad('files', f'candidate.txt: run {info.get("run")!r} or main {main!r} unreadable')
        return None
    rid = int(m.group(1))
    if keep.norm_hex(info.get('cert', '')) != ours:
        c.bad('files', f'candidate.txt: certificate {info.get("cert")} is not ours (mod/mod-cert.sha256)')
    if info.get('blocks') != '(none)' or info.get('built') != main:
        c.bad('gate', f'candidate with block branches ({info.get("blocks")}): its code is not one commit of main - '
                      'after the "ok" and the merge, a candidate of main alone is built and checked, that one is published')
    for name, h in hashes.items():
        if listed.get(name) != h:
            c.bad('files', f'{name}: sha256 {h[:12]}... differs from candidate.txt ({(listed.get(name) or "none")[:12]}...)')

    rec = record.read_text(encoding='utf-8', errors='replace')
    rec_run = re.search(r'^run:\s+(\S+)', rec, re.M)
    if not rec_run or rec_run.group(1) != info['run']:
        c.bad('files', f'record.txt names another run than candidate.txt ({rec_run and rec_run.group(1)})')
    if not re.search(r'^checks:\s+OK\s*$', rec, re.M):
        c.bad('files', 'record.txt: the checks of mod/apk-keep.py fetch were not OK')
    rec_hashes = dict((p, h) for h, p in re.findall(r'^\s+([0-9a-f]{64})\s+(\S+)$', rec, re.M))
    for name, h in hashes.items():
        if rec_hashes.get(f'dist/{name}') != h:
            c.bad('files', f'{name}: sha256 differs from record.txt written by mod/apk-keep.py fetch')
    # the release text is not in candidate.txt: it must be the one downloaded with the APKs
    if rec_hashes.get('release-notes.md') != keep.sha256(notes):
        c.bad('files', 'release-notes.md differs from the one mod/apk-keep.py fetch downloaded (record.txt)')

    # signature = ours, package, version -modN, versionCode (the checks of mod/apk-keep.py fetch, again)
    stub = {'html_url': info['run'], 'head_sha': main, 'head_branch': 'main', 'created_at': '-'}
    try:
        problems, _ = keep.check_candidate(folder, version, n, stub)
        pkg, code, vname = badging(phone)
    except subprocess.CalledProcessError as e:
        raise Stop(f'aapt2/apksigner on the kept APKs: exit {e.returncode}: {(e.stderr or "").strip()[:300]}')
    except SystemExit as e:  # mod/apk-keep.py: build-tools or Java not found
        raise Stop(str(e))
    for p in problems:
        if 'differs from candidate.txt' not in p:  # said above already
            c.bad('files', p)

    try:
        mf = json.loads(manifest.read_text(encoding='utf-8'))
        art = mf['artifacts']
        good = (mf.get('schema') == 1 and mf.get('versionName') == vname and mf.get('versionCode') == code
                and len(art) == 1 and art[0].get('file') == phone.name and art[0].get('size') == sizes[phone.name]
                and art[0].get('sha256') == hashes[phone.name] and art[0].get('applicationId') == pkg == keep.PACKAGE)
    except (ValueError, KeyError, TypeError, AttributeError):
        good = False
    if not good:
        c.bad('files', 'update-manifest.json does not describe the phone APK (version, file name, size, sha256)')

    text = notes.read_text(encoding='utf-8', errors='replace')
    if not all(s in text for s in (main, ours, phone.name, wear.name)) or re.search(r'@[A-Z]+@', text):
        c.bad('files', 'release-notes.md does not name this commit, our certificate and both APKs')

    if not c.of('files'):
        c.ok(f'kept copy {folder.name}: 3 files = candidate.txt = record.txt, signature ours, {vname} '
             f'(versionCode {code}), manifest and release text fit')
    mark = folder / keep.MARK
    installed = mark.read_text(encoding='utf-8', errors='replace').strip() if mark.is_file() else ''
    if installed:
        c.ok(f'{folder.name} is marked installed on the phone ({installed[:16]})')
    else:
        c.bad('gate', f'{folder.name} is not marked installed: only the candidate on the phone is published '
                      f'(python mod/apk-keep.py installed {folder.name}, once it is there)')
    return {'rid': rid, 'run_url': info['run'], 'main': main, 'hashes': hashes, 'sizes': sizes, 'files': files,
            'notes': notes, 'installed': installed, 'upstream': vname.rsplit('-phone-mod', 1)[0]}


# ---------- GitHub, read only ----------
def check_run(loc, n, c):
    rid = loc['rid']
    run = api(f'repos/{REPO}/actions/runs/{rid}')
    title = run.get('display_title') or ''
    m = re.fullmatch(rf'MOD candidate {n} blocks: (.+?)( \(hold=0\))?', title)
    if not (run.get('path') == WORKFLOW_PATH and run.get('event') == 'workflow_dispatch'
            and run.get('head_branch') == 'main' and (run.get('repository') or {}).get('full_name') == REPO and m):
        c.bad('files', f'run {rid} is not a manual candidate {n} run of {WORKFLOW_PATH} on main ({title!r})')
        return None
    if run.get('head_sha') != loc['main']:
        c.bad('files', f'run {rid} built {run.get("head_sha", "")[:9]}, candidate.txt says main {loc["main"][:9]}')
    if m.group(1) != '-':
        c.bad('gate', f'run {rid} merged block branches ({m.group(1)}): not one commit of main')
    loc['hold0'] = bool(m.group(2))

    job = None
    for a in range(int(run.get('run_attempt') or 1), 0, -1):
        if api(f'repos/{REPO}/actions/runs/{rid}/attempts/{a}').get('conclusion') != 'success':
            continue
        jobs = api(f'repos/{REPO}/actions/runs/{rid}/attempts/{a}/jobs?per_page=100').get('jobs') or []
        job = next((j for j in jobs if j['name'].startswith(BUILD_JOB) and j.get('conclusion') == 'success'), None)
        if job:
            break
    if not job:
        c.bad('files', f'run {rid}: no successful attempt with a successful "{BUILD_JOB}" job')
        return run
    p = sh(['gh', 'run', 'view', '--job', str(job['id']), '-R', REPO, '--log'], check=False)
    if p.returncode != 0:
        c.bad('files', f'the log of run {rid} is not readable ({p.stderr.strip()[:200]}): GitHub keeps job logs 90 '
                       'days; past that nothing on GitHub says which files that run built - build a new candidate')
        return run
    seen = {}
    for line in p.stdout.splitlines():
        mm = LOG_HASH_RE.search(line)
        if mm and mm.group(2) in loc['hashes']:
            seen.setdefault(mm.group(2), set()).add(mm.group(1))
    bad = 0
    for name, h in loc['hashes'].items():
        got = seen.get(name, set())
        if got != {h}:
            bad += 1
            c.bad('files', f'{name}: the build log of run {rid} says {sorted(x[:12] for x in got) or "nothing"}, '
                           f'the kept copy is {h[:12]}...')
    if not bad:
        c.ok(f'run {rid} ({title}, job {job["id"]}): its log has the same sha256 of the 3 files')
    return run


def check_github(loc, tag, vt, c):
    cmp = api(f'repos/{REPO}/compare/{loc["main"]}...main')
    if cmp.get('status') in ('identical', 'ahead'):
        c.ok(f'commit {loc["main"][:9]} is in main ({cmp.get("status")}, main +{cmp.get("ahead_by", 0)} commits)')
    else:
        c.bad('gate', f'commit {loc["main"][:9]} is not in main ({cmp.get("status")})')
    newer, probes = [], []
    for r in releases():
        if r['tag_name'] == tag:
            how = (' - --verify compares it with the kept copy' if not r['draft'] else
                   f' - left by an earlier attempt; delete: gh api -X DELETE repos/{REPO}/releases/{r["id"]}')
            c.bad('gate', f'a release of {tag} exists already ({"draft" if r["draft"] else "published"}, '
                          f'{r["html_url"]}){how}')
        m = REL_TAG_RE.match(r['tag_name'])
        if m and not r['draft'] and tuple(int(x) for x in m.groups()) > vt:
            newer.append(r['tag_name'])
        if r['tag_name'].startswith(PROBE_PREFIX):
            probes.append(f'{r["tag_name"]} (id {r["id"]})')
    if newer:
        c.bad('gate', f'newer release(s) published: {", ".join(newer)} - the in-app updater takes the latest '
                      f'published one, an older build after it would hide it')
    if probes:
        say(f'note  probe draft(s) left: {", ".join(probes)} - delete: gh api -X DELETE repos/{REPO}/releases/<id>')
    t = remote_tag(tag)
    if t is None:
        say(f'note  tag {tag}: not on GitHub yet; --publish sets it (annotated) on {loc["main"][:9]}')
    elif t != loc['main']:
        c.bad('gate', f'tag {tag} points to {t[:9]}, not to the candidate commit {loc["main"][:9]}')
    else:
        c.ok(f'tag {tag} is on GitHub, on {loc["main"][:9]}')
    loc['tag_exists'] = t is not None


# ---------- writes ----------
def drop_drafts(tag):
    """Delete every DRAFT of this tag (a published release is never touched). Never raises."""
    try:
        found = [r for r in releases() if r['tag_name'] == tag and r['draft']]
    except Stop as e:
        say(f'FAIL  drafts of {tag} not listed ({e}); look: gh release list -R {REPO}')
        return
    for r in found:
        try:
            api(f'repos/{REPO}/releases/{r["id"]}', 'DELETE')
            say(f'draft {tag} (id {r["id"]}) deleted')
        except Stop as e:
            say(f'FAIL  draft {tag} (id {r["id"]}) not deleted ({e}); by hand: '
                f'gh api -X DELETE repos/{REPO}/releases/{r["id"]}')


def upload_draft(tag, title, loc):
    say(f'upload {", ".join(p.name for p in loc["files"])} '
        f'({sum(loc["sizes"].values()) // (1 << 20)} MB) into a draft {tag}...')
    t0 = time.time()
    sh(['gh', 'release', 'create', tag, '-R', REPO, '--draft', '--target', loc['main'], '--title', title,
        '--notes-file', str(loc['notes']), *[str(p) for p in loc['files']]], timeout=3600)
    drafts = [r for r in releases() if r['tag_name'] == tag and r['draft']]
    if len(drafts) != 1:
        raise Stop(f'expected one draft of {tag} after the upload, found {len(drafts)}')
    if drafts[0].get('target_commitish') != loc['main'] or drafts[0].get('name') != title:
        raise Stop(f'draft {tag}: target {drafts[0].get("target_commitish")}, title {drafts[0].get("name")!r}')
    say(f'draft {tag} (id {drafts[0]["id"]}) made in {time.time() - t0:.0f} s')
    return drafts[0]


def check_assets(rel_id, loc, c, ctx):
    """GitHub's own sha256 (asset digest) and size of each file = the kept copy; exactly the 3 files."""
    assets = []
    for i in range(8):
        assets = api(f'repos/{REPO}/releases/{rel_id}').get('assets') or []
        if assets and all(a.get('digest') and a.get('state') == 'uploaded' for a in assets):
            break
        time.sleep(5)
    have = {a['name']: a for a in assets}
    bad = 0
    if set(have) != set(loc['hashes']):
        bad += 1
        c.bad('files', f'{ctx}: files {sorted(have)}, expected {sorted(loc["hashes"])}')
    for name, h in loc['hashes'].items():
        a = have.get(name)
        if a and (a.get('digest') != f'sha256:{h}' or a.get('size') != loc['sizes'][name] or a.get('state') != 'uploaded'):
            bad += 1
            c.bad('files', f'{ctx}: {name} on GitHub is {a.get("digest")}, {a.get("size")} bytes, {a.get("state")}; '
                           f'the kept copy is sha256:{h[:12]}..., {loc["sizes"][name]} bytes')
    if not bad:
        c.ok(f'{ctx}: GitHub\'s sha256 and size of the 3 files = the kept copy')
    return have


def probe(loc, version, n, title, c):
    ptag = f'{PROBE_PREFIX}{version}-{n}-{datetime.now(timezone.utc):%Y%m%d%H%M%S}'
    before = latest_tag()
    try:
        rel = upload_draft(ptag, f'PROBE {title} - draft, deleted by {SCRIPT}', loc)
        check_assets(rel['id'], loc, c, f'probe draft {ptag}')
        seen = json.loads(anon(f'https://api.github.com/repos/{REPO}/releases?per_page=100'))
        if any(r.get('id') == rel['id'] or r.get('tag_name') == ptag for r in seen):
            c.bad('files', 'the probe draft is visible to an anonymous visitor')
        else:
            c.ok('an anonymous visitor (as the in-app updater) does not see the draft')
        now = latest_tag()
        if now != before:
            c.bad('files', f'Latest changed with a draft: {before} -> {now}')
        else:
            c.ok(f'Latest is still {before}')
    finally:
        drop_drafts(ptag)
    if any(r['tag_name'] == ptag for r in releases()):
        c.bad('files', f'the probe draft {ptag} is still there')
    if remote_tag(ptag) is not None:
        c.bad('files', f'the draft made a tag {ptag}; delete it: git push origin :refs/tags/{ptag}')
    else:
        c.ok(f'no tag {ptag} on GitHub: a draft makes none, the tag comes only with --publish')


def tag_message(tag, n, loc, checked):
    today = datetime.now().strftime('%Y-%m-%d')
    hold = " (hold=0 by the owner's word)" if loc.get('hold0') else ''
    return (f'{tag}: candidate {n} on JugglucoNG {loc["upstream"]} - main {loc["main"][:9]}\n'
            f'Run: {loc["run_url"]}{hold}\n'
            f'{checked}\n'
            f'Published {today} with {SCRIPT}: the run\'s own files, sha256 in the release\n')


def committer_email():
    """The e-mail git writes into a new tag (config, or GIT_COMMITTER_EMAIL over it)."""
    m = re.search(r'<([^>]*)>', git('var', 'GIT_COMMITTER_IDENT').stdout)
    return m.group(1) if m else ''


def ensure_tag(tag, n, loc, checked):
    """The annotated tag on the candidate commit, on GitHub."""
    t = remote_tag(tag)
    if t is not None:
        if t != loc['main']:
            raise Stop(f'tag {tag} on GitHub points to {t[:9]}, not to {loc["main"][:9]}')
        say(f'OK    tag {tag} already on GitHub, on {loc["main"][:9]}')
        return
    if REPO.lower() not in git('remote', 'get-url', 'origin').stdout.strip().lower():
        raise Stop(f'origin of {ROOT} is not {REPO}')
    if git('rev-parse', '-q', '--verify', f'refs/tags/{tag}', check=False).returncode == 0:
        # made earlier (by hand or an interrupted run): pushed only if annotated, on the commit, noreply e-mail
        kind, _, email = git('for-each-ref', '--format=%(objecttype) %(taggeremail)',
                             f'refs/tags/{tag}').stdout.strip().partition(' ')
        peeled = git('rev-parse', f'refs/tags/{tag}^{{commit}}').stdout.strip()
        if kind != 'tag' or peeled != loc['main'] or not email.strip('<>').endswith(NOREPLY):
            raise Stop(f'a local tag {tag} ({kind}, on {peeled[:9]}, {"noreply" if email.strip("<>").endswith(NOREPLY) else "not a noreply e-mail"}) '
                       f'is not pushed: delete it (git -C "{ROOT}" tag -d {tag}) and run again')
        say(f'note  local tag {tag} (made earlier, annotated, on {peeled[:9]}) is pushed')
    else:
        if not committer_email().endswith(NOREPLY):
            raise Stop(f'the committer e-mail of {ROOT} is not a GitHub noreply address, and the tag is public '
                       f'(git config user.email <id>+<login>{NOREPLY})')
        if git('cat-file', '-e', f'{loc["main"]}^{{commit}}', check=False).returncode != 0:
            git('fetch', '--no-tags', 'origin', 'main')
        fd, path = tempfile.mkstemp(suffix='.txt')
        try:
            with os.fdopen(fd, 'w', encoding='utf-8', newline='\n') as f:
                f.write(tag_message(tag, n, loc, checked))
            git('tag', '-a', tag, loc['main'], '-F', path)
        finally:
            os.unlink(path)
    git('push', 'origin', f'refs/tags/{tag}:refs/tags/{tag}')
    if remote_tag(tag) != loc['main']:
        raise Stop(f'tag {tag} pushed, but GitHub does not show it on {loc["main"][:9]}')
    say(f'OK    tag {tag} pushed to GitHub, on {loc["main"][:9]}')


def publish(loc, tag, n, title, checked, c):
    """Draft -> digests -> tag -> published, Latest. Before the last step nothing is public but the tag."""
    try:
        rel = upload_draft(tag, title, loc)
        check_assets(rel['id'], loc, c, f'draft {tag}')
        if c.items:
            raise Stop('the files on GitHub differ from the kept copy')
        ensure_tag(tag, n, loc, checked)
        api(f'repos/{REPO}/releases/{rel["id"]}', 'PATCH', {'draft': False, 'make_latest': 'true'})
    except BaseException:
        drop_drafts(tag)  # drafts only: had the last call gone through after all, the release stays
        raise
    say(f'published {tag} (id {rel["id"]})')
    return rel['id']


def check_published(loc, tag, c, want_latest, rel_id=None):
    rel = api(f'repos/{REPO}/releases/tags/{tag}', missing_ok=True)  # published releases only
    if rel is None:
        c.bad('files', f'no published release of {tag}')
        return
    if rel['draft'] or rel['prerelease'] or (rel_id and rel['id'] != rel_id):
        c.bad('files', f'{tag}: draft {rel["draft"]}, pre-release {rel["prerelease"]}, id {rel["id"]}')
    have = check_assets(rel['id'], loc, c, f'release {tag}')
    t = remote_tag(tag)
    if t != loc['main']:
        c.bad('files', f'tag {tag} is on {t and t[:9]}, not on {loc["main"][:9]}')
    else:
        c.ok(f'tag {tag} on {loc["main"][:9]}')
    latest = latest_tag()
    if latest == tag:
        c.ok(f'{tag} is Latest')
    elif want_latest:
        c.bad('files', f'Latest is {latest}, not {tag}')
    else:
        say(f'note  Latest is {latest}, not {tag}')
    if want_latest:
        # as an anonymous visitor: Latest, and the newest regular release by publication date, which is what the
        # in-app updater takes (GithubReleaseParser); GitHub may answer from a cache for up to a minute
        seen = newest = None
        for i in range(7):
            seen = json.loads(anon(f'https://api.github.com/repos/{REPO}/releases/latest')).get('tag_name')
            regular = [r for r in json.loads(anon(f'https://api.github.com/repos/{REPO}/releases?per_page=30'))
                       if not r.get('draft') and not r.get('prerelease')]
            regular.sort(key=lambda r: r.get('published_at') or '', reverse=True)
            newest = regular[0]['tag_name'] if regular else None
            if seen == tag and newest == tag:
                break
            time.sleep(15)
        if seen == tag and newest == tag:
            c.ok(f'anonymous visitor: Latest and the newest by publication date is {tag}')
        else:
            c.bad('files', f'anonymous visitor: Latest {seen}, newest by publication date {newest}; expected {tag}')
    mf = have.get('update-manifest.json')
    if mf:
        h = hashlib.sha256(anon(mf['browser_download_url'], 'application/octet-stream')).hexdigest()
        if h == loc['hashes']['update-manifest.json']:
            c.ok('anonymous download of update-manifest.json = the kept copy')
        else:
            c.bad('files', f'anonymous download of update-manifest.json is {h[:12]}..., not the kept copy')
    say(f'page: {rel["html_url"]}')


def after_failure(loc, tag, err):
    """--publish stopped half-way: what is on GitHub now? A lost answer may hide a release that did go out."""
    say(f'STOP  {err}' if isinstance(err, Stop) else f'STOP  {type(err).__name__}: {err}')
    try:
        rel = api(f'repos/{REPO}/releases/tags/{tag}', missing_ok=True)  # published only
        t = remote_tag(tag)
    except Stop as e:
        say(f'FAIL  what is on GitHub now is unknown ({e}): look at https://github.com/{REPO}/releases, then '
            f'{SCRIPT} {tag} --verify')
        return 2
    if rel is not None and not rel.get('draft'):
        say(f'the release {tag} IS published (GitHub took the last step before the error); the checks after it:')
        c = Checks()
        try:
            check_published(loc, tag, c, want_latest=True)
        except (Exception, KeyboardInterrupt) as e:
            c.bad('files', f'checks after publishing stopped: {type(e).__name__}: {e}')
        say('PUBLISHED; ' + ('checks OK' if not c.items else f'{len(c.items)} check(s) failed: look at the page'))
        return 0 if not c.items else 2
    if t is not None:
        say(f'note  tag {tag} is on GitHub (on {t[:9]}); --publish again uses it')
    say('nothing published')
    return 1


class Args(argparse.ArgumentParser):
    def error(self, message):
        self.print_usage(sys.stderr)
        self.exit(64, f'{self.prog}: {message}\n')


def main():
    ap = Args(description=__doc__.split('\n')[0], formatter_class=argparse.RawDescriptionHelpFormatter,
              epilog='\n'.join(__doc__.split('\n')[2:]))
    ap.add_argument('name', help='mod-X.Y.Z-N, a folder kept by mod/apk-keep.py')
    mode = ap.add_mutually_exclusive_group()
    mode.add_argument('--probe', action='store_true', help='upload into a draft under a probe tag, then delete it')
    mode.add_argument('--publish', action='store_true', help='publish for everyone (normal, Latest)')
    mode.add_argument('--verify', action='store_true', help='read only: a published release = the kept copy')
    ap.add_argument('--checked', help='with --publish, required: the owner\'s "all ok" - what was checked, where, '
                                      'when (one line of plain English, nothing personal; goes into the public tag)')
    ap.add_argument('--dest', type=Path, default=ROOT.parent / 'apk', help='where mod/apk-keep.py keeps candidates')
    a = ap.parse_args()
    m = keep.DIR_RE.match(a.name)
    if not m:
        ap.error(f'{a.name!r} is not mod-X.Y.Z-N')
    if a.checked is not None and not a.publish:
        ap.error('--checked goes with --publish')
    if a.publish and not re.fullmatch(r'[ -~]{10,200}', a.checked or ''):
        ap.error('--publish needs --checked "what the owner checked, where, when": the owner\'s "all ok", '
                 'one line of 10-200 plain ASCII characters')
    version, n = m.group(1), int(m.group(2))
    tag = a.name
    vt = tuple(int(x) for x in version.split('.')) + (n,)
    title = f'JugglucoNGmod {version}-{n}'
    what = 'probe' if a.probe else 'publish' if a.publish else 'verify' if a.verify else 'check (nothing written)'
    say(f'{what}: {tag} from {a.dest / tag}')
    c = Checks(gates=not a.verify)
    try:
        loc = check_local(a.dest / tag, version, n, c)
        if loc is None:
            return 1
        if a.verify:
            check_published(loc, tag, c, want_latest=False)
            return 1 if c.items else 0
        check_run(loc, n, c)
        check_github(loc, tag, vt, c)
        files, gates = c.of('files'), c.of('gate')
        if files:
            say(f'nothing uploaded: {len(files)} problem(s) with the files or the run')
            return 1
        if a.probe:
            if gates:
                say(f'note  --publish would refuse now: {"; ".join(gates)}')
            c.items = []
            probe(loc, version, n, title, c)
            say('probe: ' + ('OK - nothing published, the draft is gone' if not c.items else 'FAILED'))
            return 1 if c.items else 0
        if gates:
            say(f'nothing published: {len(gates)} reason(s) above')
            return 1
        if not a.publish:
            say(f'ready: after the owner\'s "all ok", --publish --checked "..." makes {tag} a normal release and '
                f'Latest (now {latest_tag()}): "{title}", the 3 files above, text = the run\'s release-notes.md, '
                f'tag {"on GitHub" if loc["tag_exists"] else "set then"} on {loc["main"][:9]}'
                + (', hold=0 candidate' if loc.get('hold0') else ''))
            return 0
    except Stop as e:
        say(f'STOP  {e}')
        say('nothing published')
        return 1
    try:
        rel_id = publish(loc, tag, n, title, a.checked, c)
    except (Exception, KeyboardInterrupt) as e:
        return after_failure(loc, tag, e)
    try:
        check_published(loc, tag, c, want_latest=True, rel_id=rel_id)
    except (Exception, KeyboardInterrupt) as e:
        c.bad('files', f'checks after publishing stopped: {type(e).__name__}: {e}')
    if c.items:
        say(f'PUBLISHED, but {len(c.items)} check(s) after it failed: look at the release page')
        return 2
    say(f'done: {tag} is published for everyone - normal, Latest, the 3 files of run {loc["rid"]}')
    return 0


if __name__ == '__main__':
    sys.exit(main())
