#!/usr/bin/env python3
"""JugglucoNGmod: checks on built `mod` APKs. Every check prints OK or FAIL; any FAIL -> exit 1.

Run from the repository root after `bash mod/gradlew-mod.sh ... assembleMobileMod assembleWearMod`:
  python3 mod/check-apk.py --phone PATH --wear PATH --release N --mode test|release [--no-vendor]

--mode test     signature must differ from both upstream keys (one-time key of the run)
--mode release  additionally the signature must equal mod/mod-cert.sha256
--no-vendor     skip the vendor library check (emulator x86_64 build has no vendor libraries)
--manifest P    update-manifest.json must describe the phone APK (required with --mode release)

  python3 mod/check-apk.py --trial-absent PHONE_APK
                only: a phone APK built with -PmodRelease=N (N >= 1) has no CareLink trial (marker strings)
"""
import argparse
import fnmatch
import glob
import hashlib
import json
import os
import re
import subprocess
import sys
import zipfile

PACKAGE = 'tk.glucodata.ng.mod'
APP_NAME = 'JugglucoNGmod'
# Our own watch face (watch block, part 3b) carries this name; upstream's face keeps theirs as a spare.
MOD_WATCHFACE_NAME = 'JugglucoNGmod'
# Our components that must be in the wear APK (declared in Common/src/wearMod/AndroidManifest.xml).
MOD_WEAR_COMPONENTS = ['tk.glucodata.mod.watch.SnoozeButtonActivity', 'tk.glucodata.mod.watch.ModWearListener',
                       'tk.glucodata.mod.watch.face.ModWatchFaceService',
                       'tk.glucodata.mod.watch.face.FaceSettingsActivity',
                       # heart rate and steps (watch part 4b)
                       'tk.glucodata.mod.health.HealthActivity', 'tk.glucodata.mod.health.hs.ModHealthListenerService',
                       'tk.glucodata.mod.health.hs.ModHealthStarter', 'tk.glucodata.mod.health.hs.ModHealthBootReceiver',
                       # our alarm screen and its starter (watch part 8)
                       'tk.glucodata.mod.watch.alarm.ModAlarmActivity', 'tk.glucodata.mod.watch.alarm.ModAlarmStarter']
# Permissions the wear APK must request, exact names (watch part 4b; upstream's watch manifest has
# 'BODY_SENSORS_BACKGROUND ' with a trailing space, which no system knows).
MOD_WEAR_PERMISSIONS = ['android.permission.BODY_SENSORS', 'android.permission.BODY_SENSORS_BACKGROUND',
                        'android.permission.health.READ_HEART_RATE',
                        'android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND',
                        'android.permission.ACTIVITY_RECOGNITION', 'android.permission.RECEIVE_BOOT_COMPLETED']
# Upstream's watch minSdk: Health Services (minSdk 30) is overridden in our manifest, never raises it.
UPSTREAM_WEAR_MIN_SDK = '26'
# Our phone components of the watch block (declared in Common/src/mobileMod/AndroidManifest.xml, watch parts 1, 2, 5).
MOD_PHONE_COMPONENTS = ['tk.glucodata.mod.watch.ModPhoneListener', 'tk.glucodata.mod.watch.ModWatchStarter',
                        'tk.glucodata.mod.watch.ModWatchActivity']
# Must match packagingOptions.jniLibs.excludes in upstream Common/build.gradle.
VENDOR_EXCLUDES = ['libdata-handle-lib*.so', 'libnative-algorithm-*.so', 'libnative-algorithm-jni-*.so',
                   'libnative-encrypy-decrypt-v110.so', 'libnative-sensitivity-v110.so', 'libnative-struct2json.so']

failures = []


def ok(msg):
    print(f'OK    {msg}')


def fail(msg):
    print(f'FAIL  {msg}')
    failures.append(msg)


def check(cond, msg):
    (ok if cond else fail)(msg)
    return cond


def run(cmd):
    # errors='replace': dexdump prints raw bytes of dex strings, not always valid UTF-8
    return subprocess.run(cmd, check=True, capture_output=True, text=True, encoding='utf-8', errors='replace').stdout


def build_tool(name):
    home = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    found = sorted(glob.glob(os.path.join(home, 'build-tools', '*', name)))
    if not found:
        sys.exit(f'{name} not found under {home}/build-tools')
    return found[-1]


def badging(apk):
    out = run([build_tool('aapt2'), 'dump', 'badging', apk])
    m = re.search(r"package: name='([^']*)' versionCode='([^']*)' versionName='([^']*)'", out)
    labels = dict(re.findall(r"^application-label(-[^:]*)?:'([^']*)'", out, re.M))
    return m.group(1), int(m.group(2)), m.group(3), labels


def norm_hex(s):
    return re.sub(r'[^0-9a-f]', '', s.lower())


def apk_cert(apk):
    out = run([build_tool('apksigner'), 'verify', '--print-certs', apk])
    certs = re.findall(r'certificate SHA-256 digest: ([0-9a-fA-F]+)', out)
    return {norm_hex(c) for c in certs}


def upstream_certs():
    certs = {}
    certs['upstream production (scripts/dist/production-cert.sha256)'] = norm_hex(
        open('scripts/dist/production-cert.sha256').read())
    out = run(['keytool', '-list', '-v', '-keystore', 'Common/everyone.keystore',
               '-storepass', 'IDontKnow', '-alias', 'thealias'])
    m = re.search(r'SHA256:\s*([0-9A-F:]+)', out)
    certs['upstream everyone.keystore'] = norm_hex(m.group(1))
    return certs


def build_gradle_value(name):
    text = open('Common/build.gradle', encoding='utf-8').read()
    m = re.search(rf"^\s*{name}\s*=\s*'?([^'\s]+)'?", text, re.M)
    return m.group(1)


def buildconfig_version_code(variant_dir):
    """VERSION_CODE from the generated BuildConfig: .java source, or compiled .class / .jar (AGP may emit bytecode)."""
    rx = re.compile(r'VERSION_CODE\s*=\s*(\d+)')
    for f in glob.glob(f'Common/build/generated/**/{variant_dir}/**/BuildConfig.java', recursive=True):
        m = rx.search(open(f, encoding='utf-8').read())
        if m:
            print(f'      BuildConfig: {f}')
            return int(m.group(1))
    targets = glob.glob(f'Common/build/intermediates/**/{variant_dir}/**/tk/glucodata/BuildConfig.class', recursive=True)
    for jar in glob.glob(f'Common/build/intermediates/**/{variant_dir}/**/*.jar', recursive=True):
        try:
            with zipfile.ZipFile(jar) as z:
                if 'tk/glucodata/BuildConfig.class' in z.namelist():
                    targets.append(jar)
        except zipfile.BadZipFile:
            pass
    for t in targets:
        cmd = ['javap', '-constants', '-cp', t, 'tk.glucodata.BuildConfig'] if t.endswith('.jar') \
            else ['javap', '-constants', t]
        m = rx.search(run(cmd))
        if m:
            print(f'      BuildConfig: {t}')
            return int(m.group(1))
    found = [f for f in glob.glob('Common/build/**/BuildConfig*', recursive=True) if variant_dir in f]
    print(f'      BuildConfig not found for {variant_dir}; candidates: {found[:10]}')
    return None


def check_names():
    """For every upstream values* dir that defines one of our overridden names, our source set defines it too
    (a new upstream locale with its own app_name or watch_face_name would show the upstream name there)."""
    # watch_face_name: upstream's face, renamed by our wearMod strings (our own face is mod_watch_face_name)
    names = (('app_name', 'mod', APP_NAME), ('watch_face_name', 'wearMod', upstream_watchface_name()))
    for name, our_src, value in names:
        upstream = set()
        for f in glob.glob('Common/src/*/res/values*/strings.xml'):
            parts = f.replace('\\', '/').split('/')
            if parts[2] in ('mod', 'mobileMod', 'wearMod'):
                continue
            if re.search(rf'name="{name}"', open(f, encoding='utf-8').read()):
                upstream.add(parts[4])
        ours = set()
        for f in glob.glob(f'Common/src/{our_src}/res/values*/strings.xml'):
            if re.search(rf'name="{name}"[^>]*>{re.escape(value)}<', open(f, encoding='utf-8').read()):
                ours.add(f.replace('\\', '/').split('/')[4])
        missing = sorted(upstream - ours)
        check(not missing and upstream, f'{name} "{value}" in {our_src} for all {len(upstream)} upstream values* dirs'
              + (f' (missing: {missing})' if missing else ''))


def check_icon():
    for f in glob.glob('Common/src/main/res/mipmap-anydpi-v26/ic_launcher*.xml'):
        check('@drawable/ic_launcher_background' in open(f, encoding='utf-8').read(),
              f'{f} still uses @drawable/ic_launcher_background (our icon background applies)')
    check(os.path.isfile('Common/src/mod/res/drawable/ic_launcher_background.xml'), 'our icon background exists')


def check_native():
    files = glob.glob('Common/.cxx/**/compile_commands.json', recursive=True)
    if not check(bool(files), f'compile_commands.json found ({len(files)})'):
        return
    for f in files:
        t = open(f, encoding='utf-8').read()
        check('-DAPPSUFFIX' in t and 'quotes(.mod)' in t and '-DRELEASEDIR=1' in t,
              f'native flags -DAPPSUFFIX=.mod and -DRELEASEDIR=1 in {f}')


def check_vendor(apk):
    inputs = json.load(open('scripts/dist/build-inputs.json'))['files']
    with zipfile.ZipFile(apk) as z:
        names = set(z.namelist())
        bad = []
        expected = 0
        for item in inputs:
            parts = item['path'].split('/')
            abi, lib = parts[-2], parts[-1]
            entry = f'lib/{abi}/{lib}'
            excluded = any(fnmatch.fnmatch(lib, pat) for pat in VENDOR_EXCLUDES)
            if excluded:
                if entry in names:
                    bad.append(f'{entry} should be excluded')
                continue
            expected += 1
            if entry not in names:
                bad.append(f'{entry} missing')
            elif hashlib.sha256(z.read(entry)).hexdigest() != item['sha256']:
                bad.append(f'{entry} sha256 differs')
    check(not bad, f'vendor libraries in phone APK: {expected} expected, hashes = scripts/dist/build-inputs.json'
          + (f' — {bad}' if bad else ''))


def upstream_watchface_name():
    """Upstream's face name as our watch app shows it: renamed by our Common/src/wearMod/res/values/strings.xml to
    "JugglucoNGmod classic" (watch part 3c2), so the face list tells it from ours and from the official app's."""
    m = re.search(r'name="watch_face_name"[^>]*>([^<]*)<',
                  open('Common/src/wearMod/res/values/strings.xml', encoding='utf-8').read())
    return m.group(1) if m else None


def check_resource_string(dump, name, want, what):
    m = re.search(rf'string/{re.escape(name)}\b.*?\n\s*\(\)\s*"([^"]*)"', dump, re.S)
    check(m and m.group(1) == want, f'{what} "{want}" in wear APK'
          + ('' if m else ' (not found)') + (f' (got "{m.group(1)}")' if m and m.group(1) != want else ''))


def check_watchface_name(apk):
    out = run([build_tool('aapt2'), 'dump', 'resources', apk])
    check_resource_string(out, 'watch_face_name', upstream_watchface_name(), "upstream's watch face name")
    check_resource_string(out, 'mod_watch_face_name', MOD_WATCHFACE_NAME, 'our watch face name')


def novalue_fill_colors(apk):
    """The fill colours of the vector `drawable/novalue` in [apk] (its anydpi file; paths may be shortened)."""
    out = run([build_tool('aapt2'), 'dump', 'resources', apk])
    m = re.search(r'drawable/novalue\n((?:\s+\(.*\n)+)', out)
    files = re.findall(r'\(anydpi[^)]*\) \(file\) (\S+) type=XML', m.group(1)) if m else []
    if not files:
        return None
    tree = run([build_tool('aapt2'), 'dump', 'xmltree', '--file', files[0], apk])
    return set(c.lower() for c in re.findall(r'fillColor\([^)]*\)=(#[0-9a-fA-F]+)', tree))


def check_novalue(wear, phone):
    """Watch part 7c: the icon of upstream's ongoing activity, drawn by Wear OS at the bottom of every face, is our
    faint dot in the wear APK (Common/src/wearMod/res/drawable-anydpi-v24/novalue.xml overrides theirs); the phone
    keeps upstream's."""
    ours = set(c.lower() for c in re.findall(r'android:fillColor="(#[0-9A-Fa-f]+)"',
               open('Common/src/wearMod/res/drawable-anydpi-v24/novalue.xml', encoding='utf-8').read()))
    in_wear = novalue_fill_colors(wear)
    check(in_wear == ours, f'wear APK: novalue is our faint dot {sorted(ours)}' + ('' if in_wear == ours else f' (got {in_wear})'))
    in_phone = novalue_fill_colors(phone)
    check(in_phone is not None and in_phone != ours, "phone APK: novalue is upstream's" + (f' (got {in_phone})' if in_phone == ours or in_phone is None else ''))


def check_wear_components(apk):
    """Our watch components are in the wear APK's manifest; our face is a wallpaper service without an editor action
    (upstream's WATCH_FACE_EDITOR would open their WatchFaceConfigActivity, any other value hides the face), and our
    editor is an app entry of its own ("Graph hours")."""
    out = run([build_tool('aapt2'), 'dump', 'xmltree', '--file', 'AndroidManifest.xml', apk])
    names = set(re.findall(r'A: (?:http://schemas.android.com/apk/res/android:)?name\([^)]*\)="([^"]+)"', out))
    missing = [c for c in MOD_WEAR_COMPONENTS if c not in names]
    check(not missing, f'wear APK declares our {len(MOD_WEAR_COMPONENTS)} components'
          + (f' (missing: {missing})' if missing else ''))
    block = editor = ''
    for m in re.finditer(r'E: (?:service|activity)\b(?:(?!\n\s*E: (?:service|activity|receiver|provider)\b)[\s\S])*',
                         out):
        if 'tk.glucodata.mod.watch.face.ModWatchFaceService' in m.group(0):
            block = m.group(0)
        if 'tk.glucodata.mod.watch.face.FaceSettingsActivity' in m.group(0):
            editor = m.group(0)
    check('android.permission.BIND_WALLPAPER' in block and 'android.service.wallpaper.WallpaperService' in block
          and 'wearableConfigurationAction' not in block,
          'our watch face: BIND_WALLPAPER, wallpaper service filter, no editor action (Wear OS leaves a face with an '
          'action other than WATCH_FACE_EDITOR out of the picker - watch, 18.11.2026)')
    check('WEARABLE_CONFIGURATION' not in editor and 'WATCH_FACE_EDITOR' not in editor,
          'our watch face editor: not a face editor by action (no WEARABLE_CONFIGURATION, no WATCH_FACE_EDITOR)')
    check('android.intent.action.MAIN' in editor and 'android.intent.category.LAUNCHER' in editor,
          'our watch face editor: own launcher entry "Graph hours" (watch part 3c2)')
    check_wear_alarm(apk, out)
    check_wear_health(apk, out)


def check_wear_alarm(apk, xmltree):
    """Our alarm screen (watch part 8): private, over the lock screen, turns the screen on, one instance - as
    upstream's; its starter private."""
    blocks = component_blocks(xmltree)
    screen = blocks.get('tk.glucodata.mod.watch.alarm.ModAlarmActivity', '')
    false = r'=(?:false|\(type 0x12\)0x0)\b'
    true = r'=(?:true|\(type 0x12\)0xffffffff)\b'
    check(re.search(r'exported\([^)]*\)' + false, screen) is not None
          and re.search(r'showWhenLocked\([^)]*\)' + true, screen) is not None
          and re.search(r'turnScreenOn\([^)]*\)' + true, screen) is not None
          and re.search(r'launchMode\([^)]*\)=(?:2|\(type 0x10\)0x2|singleTask)\b', screen) is not None,
          'our alarm screen: not exported, showWhenLocked, turnScreenOn, singleTask')
    starter = blocks.get('tk.glucodata.mod.watch.alarm.ModAlarmStarter', '')
    check(re.search(r'exported\([^)]*\)' + false, starter) is not None, 'alarm screen starter: not exported')


def check_phone_components(apk):
    """Our phone watch-block components (watch part 5, joints critic N2): the Data Layer listener with the snooze
    messages, the watch-app capability and the heart rate and steps batches (DATA_CHANGED on /mod/health/batch/),
    the private starter and the check screen, not exported (opened by our dynamic shortcut)."""
    out = run([build_tool('aapt2'), 'dump', 'xmltree', '--file', 'AndroidManifest.xml', apk])
    blocks = component_blocks(out)
    missing = [c for c in MOD_PHONE_COMPONENTS if c not in blocks]
    check(not missing, f'phone APK declares our {len(MOD_PHONE_COMPONENTS)} watch components'
          + (f' (missing: {missing})' if missing else ''))
    listener = blocks.get('tk.glucodata.mod.watch.ModPhoneListener', '')
    check('com.google.android.gms.wearable.MESSAGE_RECEIVED' in listener
          and 'com.google.android.gms.wearable.DATA_CHANGED' in listener and '="/mod/health/batch/"' in listener,
          'phone listener: /mod/ messages and DATA_CHANGED of /mod/health/batch/')
    screen = blocks.get('tk.glucodata.mod.watch.ModWatchActivity', '')
    check(re.search(r'exported\([^)]*\)=(?:false|\(type 0x12\)0x0)\b', screen) is not None,
          'phone watch screen: not exported')
    starter = blocks.get('tk.glucodata.mod.watch.ModWatchStarter', '')
    check(re.search(r'exported\([^)]*\)=(?:false|\(type 0x12\)0x0)\b', starter) is not None, 'phone watch starter: not exported')


def component_blocks(xmltree):
    """Each activity/service/receiver/provider element of an `aapt2 dump xmltree` manifest, by its name."""
    blocks = {}
    for m in re.finditer(r'E: (?:service|activity|receiver|provider)\b'
                         r'(?:(?!\n\s*E: (?:service|activity|receiver|provider|application)\b)[\s\S])*', xmltree):
        name = re.search(r'A: (?:http://schemas.android.com/apk/res/android:)?name\([^)]*\)="([^"]+)"', m.group(0))
        if name:
            blocks[name.group(1)] = m.group(0)
    return blocks


def check_wear_health(apk, xmltree):
    """Heart rate and steps (watch part 4b): exact permission names, the Health Services binding of our listener, the
    boot receiver's actions, the page's launcher entry, a private starter, and upstream's minSdk kept."""
    perms = set(re.findall(r"^uses-permission(?:-sdk-23)?: name='([^']*)'", run([build_tool('aapt2'), 'dump', 'permissions', apk]), re.M))
    missing = [p for p in MOD_WEAR_PERMISSIONS if p not in perms]
    check(not missing, f'wear APK requests our {len(MOD_WEAR_PERMISSIONS)} health permissions by their exact names'
          + (f' (missing: {missing})' if missing else ''))
    blocks = component_blocks(xmltree)
    listener = blocks.get('tk.glucodata.mod.health.hs.ModHealthListenerService', '')
    check('com.google.android.wearable.healthservices.permission.PASSIVE_DATA_BINDING' in listener,
          'health listener: bound only by Health Services (PASSIVE_DATA_BINDING)')
    boot = blocks.get('tk.glucodata.mod.health.hs.ModHealthBootReceiver', '')
    check('android.intent.action.BOOT_COMPLETED' in boot and 'android.intent.action.MY_PACKAGE_REPLACED' in boot,
          'health boot receiver: BOOT_COMPLETED and MY_PACKAGE_REPLACED')
    page = blocks.get('tk.glucodata.mod.health.HealthActivity', '')
    check('android.intent.category.LAUNCHER' in page, 'health page: own launcher entry')
    starter = blocks.get('tk.glucodata.mod.health.hs.ModHealthStarter', '')
    check(re.search(r'exported\([^)]*\)=(?:false|\(type 0x12\)0x0)\b', starter) is not None, 'health starter: not exported')
    sdk = re.search(r"^(?:minSdkVersion|sdkVersion):'([^']*)'", run([build_tool('aapt2'), 'dump', 'badging', apk]), re.M)
    check(sdk is not None and sdk.group(1) == UPSTREAM_WEAR_MIN_SDK,
          f"wear minSdk {sdk.group(1) if sdk else '?'} (upstream's {UPSTREAM_WEAR_MIN_SDK}; Health Services overridden)")


def const_only_resources():
    """Resources the wear sources reach via `const val X = R.type.name`, plus every upstream *_style_name.

    R8 resource shrinking misses these references (upstream 1.3.2 watch face crash: "String resource ID #0x0");
    Common/src/wearMod/res/raw/mod_keep.xml keeps them. Taken from the sources, so a new color fails loudly."""
    res = set()
    for f in glob.glob('Common/src/wear/java/**/*.kt', recursive=True):
        res.update(re.findall(r'const\s+val\s+\w+\s*(?::\s*Int\s*)?=\s*R\.(\w+)\.(\w+)',
                              open(f, encoding='utf-8').read()))
    strings = open('Common/src/wear/res/values/strings.xml', encoding='utf-8').read()
    res.update(('string', n) for n in re.findall(r'name="(\w+_style_name)"', strings))
    return sorted(res)


def check_watchface_resources(apk):
    """Each const-only resource exists in the wear APK and its id is used as a literal in the wear dex code."""
    wanted = const_only_resources()
    if not check(len(wanted) >= 13, f'const-only watch face resources found in sources: {len(wanted)}'):
        return
    out = run([build_tool('aapt2'), 'dump', 'resources', apk])
    ids = {(t, n): int(i, 16) for i, t, n in re.findall(r'resource (0x[0-9a-f]+) (\w+)/(\S+)', out)}
    missing = [f'{t}/{n}' for t, n in wanted if (t, n) not in ids]
    check(not missing, f'wear APK keeps {len(wanted)} watch face resources (mod_keep.xml)'
          + (f' (missing: {missing})' if missing else ''))
    literals = set()
    with zipfile.ZipFile(apk) as z:
        dexes = [n for n in z.namelist() if re.fullmatch(r'classes\d*\.dex', n)]
        tmp = os.path.join(os.environ.get('RUNNER_TEMP', '/tmp'), 'mod-wear-dex')
        os.makedirs(tmp, exist_ok=True)
        for n in dexes:
            path = z.extract(n, tmp)
            code = run([build_tool('dexdump'), '-d', path])
            literals.update(int(h, 16) for h in re.findall(r'\bconst v\d+, .*// #([0-9a-f]{8})$', code, re.M))
            literals.update(int(h, 16) << 16 for h in re.findall(r'\bconst/high16 v\d+, .*// #([0-9a-f]{4})$', code, re.M))
            os.remove(path)
    unused = [f'{t}/{n}' for t, n in wanted if (t, n) in ids and ids[(t, n)] not in literals]
    check(not missing and not unused, f'wear code uses the real ids of {len(wanted)} watch face resources (not 0)'
          + (f' (not referenced: {unused})' if unused else ''))
    # Wear is built with the previous resource shrinker (mod/gradlew-mod.sh): no id in the code may point to a
    # resource it removed. Literals of a resource type that exists in the APK are taken as resource ids.
    types = {i >> 16 for i in ids.values()}
    known = set(ids.values())
    dangling = sorted(hex(x) for x in literals if x >> 16 in types and x not in known)
    check(not dangling, f'wear code: every resource id in the code exists in the APK ({len(literals)} literals)'
          + (f' (missing: {dangling[:20]})' if dangling else ''))


def xml_drawable_classes():
    """Classes that drawable XML of the wear sources inflates by name (<drawable class="..."> or <a.b.C>)."""
    found = {}
    for f in glob.glob('Common/src/wear*/res/drawable*/*.xml'):
        m = re.search(r'<(?:drawable\s[^>]*?class="([\w.$]+)"|([a-z]\w*(?:\.\w+)+)[\s>/])', open(f, encoding='utf-8').read())
        if m:
            found.setdefault(m.group(1) or m.group(2), []).append(os.path.basename(f))
    return found


def check_xml_drawable_constructors(apk):
    """Each class inflated from drawable XML keeps its no-arg constructor in the wear dex (mod/proguard-mod.pro).

    Wear `mod` uses the previous resource shrinker, which does not keep these constructors itself: without the
    rule the watch face crashed with NoSuchMethodException: ComplicationDrawable.<init> []."""
    classes = xml_drawable_classes()
    if not check(classes, f'classes inflated from wear drawable XML found in sources: {sorted(classes)}'):
        return
    ctors = set()
    with zipfile.ZipFile(apk) as z:
        tmp = os.path.join(os.environ.get('RUNNER_TEMP', '/tmp'), 'mod-wear-dex-ctor')
        os.makedirs(tmp, exist_ok=True)
        for n in [n for n in z.namelist() if re.fullmatch(r'classes\d*\.dex', n)]:
            path = z.extract(n, tmp)
            cls, after_init = None, False
            for line in run([build_tool('dexdump'), path]).splitlines():
                m = re.match(r"\s*Class descriptor\s*:\s*'L([^;]+);'", line)
                if m:
                    cls, after_init = m.group(1).replace('/', '.'), False
                elif re.match(r"\s*name\s*:\s*'<init>'", line):
                    after_init = True
                else:
                    if after_init and cls and re.match(r"\s*type\s*:\s*'\(\)V'", line):
                        ctors.add(cls)
                    after_init = False
            os.remove(path)
    for c, files in sorted(classes.items()):
        check(c in ctors, f'wear dex: {c} has its no-arg constructor (inflated from {len(files)} drawable XML)')


MOD_STARTER = 'tk.glucodata.mod.carelink.ModCareLinkStarter'
# Strings that only the CareLink trial code has (part 4p; Common/src/modTrial and .../mod/carelink/trial): the command
# action, the log tag, the address of the trial session. Release builds rename classes (R8, no -dontobfuscate), so the
# trial is found by these, not by class names.
TRIAL_MARKERS = [b'tk.glucodata.ng.mod.CARELINK_TRIAL', b'ModCareLinkTrial', b'trial.invalid']


def dex_marker_hits(apk):
    """Which trial markers occur in the raw dex files of [apk] (the string pool holds them whole)."""
    found = set()
    with zipfile.ZipFile(apk) as z:
        dexes = [n for n in z.namelist() if re.fullmatch(r'classes\d*\.dex', n)]
        for n in dexes:
            raw = z.read(n)
            found.update(m.decode() for m in TRIAL_MARKERS if m in raw)
    return dexes, found


def check_trial(phone, release):
    """Test build (N = 0): every trial marker is in the phone dex - otherwise the trial vanished silently.
    Candidate (N >= 1): none of them, in the dex or in the manifest."""
    dexes, found = dex_marker_hits(phone)
    names = [m.decode() for m in TRIAL_MARKERS]
    if release == 0:
        missing = sorted(set(names) - found)
        check(dexes and not missing, f'phone dex (test build): CareLink trial markers present {sorted(found)}'
              + (f' (missing: {missing})' if missing else ''))
    else:
        tree = manifest_tree(phone)
        in_manifest = [m for m in names if m in tree]
        check(dexes and not found and not in_manifest,
              f'phone (candidate -mod{release}): no CareLink trial marker in {len(dexes)} dex file(s) or the manifest'
              + (f' (found: {sorted(found) + in_manifest})' if found or in_manifest else ''))


def manifest_tree(apk):
    return run([build_tool('aapt2'), 'dump', 'xmltree', '--file', 'AndroidManifest.xml', apk])


def check_mod_components(phone, wear, release):
    """Our phone-only code (CareLink, stage 4): components declared in Common/src/mobileMod/AndroidManifest.xml are
    in the phone APK; the watch gets none of our classes. Searched with the dot / slash after `mod`: the package
    itself is tk.glucodata.ng.mod, our classes are tk.glucodata.mod.* The CareLink trial: in a test build, not in a
    candidate (check_trial)."""
    check_trial(phone, release)
    tree = manifest_tree(phone)
    check(MOD_STARTER in tree and f'{PACKAGE}.mod-carelink' in tree,
          f'phone manifest: {MOD_STARTER} (authority {PACKAGE}.mod-carelink) - source set mobileMod is in the build')
    wtree = manifest_tree(wear)
    bad = sorted(set(re.findall(r'tk\.glucodata\.mod\.[\w.$]+', wtree)))
    check(not bad and 'mod-carelink' not in wtree,
          'wear manifest: none of our components (tk.glucodata.mod.*)' + (f' (found: {bad[:10]})' if bad else ''))
    hits = set()
    with zipfile.ZipFile(wear) as z:
        tmp = os.path.join(os.environ.get('RUNNER_TEMP', '/tmp'), 'mod-wear-dex-ours')
        os.makedirs(tmp, exist_ok=True)
        dexes = [n for n in z.namelist() if re.fullmatch(r'classes\d*\.dex', n)]
        for n in dexes:
            path = z.extract(n, tmp)
            hits.update(re.findall(r'Ltk/glucodata/mod/[\w/$]+', run([build_tool('dexdump'), path])))
            with open(path, 'rb') as f:
                raw = f.read()
            if b'tk/glucodata/mod/' in raw or b'tk.glucodata.mod.' in raw:
                hits.add(f'(string in {n})')
            os.remove(path)
    check(dexes and not hits, f'wear dex: none of our classes tk/glucodata/mod/ in {len(dexes)} dex file(s)'
          + (f' (found: {sorted(hits)[:10]})' if hits else ''))


def check_upstream_hooks():
    """Upstream files of HEAD: only MOD-HOOK changes within the budget - mod/check-hooks.py (marks <-> mod/HOOKS.md,
    every changed hunk next to a mark, at most 15 files and 60 lines). Replaced "0 changes in upstream files" so that
    the first hook does not turn every build red; needs upstream/main fetched."""
    p = subprocess.run([sys.executable, os.path.join(os.path.dirname(os.path.abspath(__file__)), 'check-hooks.py'),
                        '--ref', 'HEAD'], capture_output=True, text=True, encoding='utf-8', errors='replace')
    for line in (p.stdout + p.stderr).strip().splitlines():
        print('      ' + line)
    check(p.returncode == 0, 'upstream files: only hooks, within the budget (mod/check-hooks.py, lines above)')


def check_manifest(path, phone, phone_code):
    """update-manifest.json = the phone APK: package, versionCode, file name, size, sha256; schema 1."""
    m = json.load(open(path, encoding='utf-8'))
    check(m.get('schema') == 1, f'manifest: schema {m.get("schema")} (updater understands 1)')
    check(m.get('versionCode') == phone_code, f'manifest: versionCode {m.get("versionCode")} = phone APK {phone_code}')
    arts = m.get('artifacts') or []
    check(len(arts) == 1 and arts[0].get('applicationId') == PACKAGE,
          f'manifest: exactly one artifact, for {PACKAGE} (wear never offered to a phone)')
    if arts:
        data = open(phone, 'rb').read()
        art = arts[0]
        check(art.get('file') == os.path.basename(phone), f'manifest: file {art.get("file")} = phone asset name')
        check(art.get('size') == len(data), f'manifest: size {art.get("size")} = {len(data)}')
        check(art.get('sha256') == hashlib.sha256(data).hexdigest(), 'manifest: sha256 = phone APK')


def trial_absent(phone):
    """--trial-absent: a phone APK built like a candidate (-PmodRelease=1, one-time key) inside the test build job has
    no CareLink trial. Only that - no wear, signature or update manifest checks."""
    pkg, code, name, _ = badging(phone)
    m = re.search(r'-mod(\d+)$', name)
    release = int(m.group(1)) if m else 0
    check(pkg == PACKAGE and release >= 1, f'phone: package {pkg}, versionName {name} is a candidate build (-modN, N >= 1)')
    if release >= 1:
        check_trial(phone, release)
    print(f'\n{len(failures)} check(s) failed' if failures else '\nAll checks passed')
    sys.exit(1 if failures else 0)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--trial-absent', metavar='PHONE_APK',
                    help='only: the CareLink trial is not in this candidate-like phone APK (part 4p)')
    ap.add_argument('--phone')
    ap.add_argument('--wear')
    ap.add_argument('--release', type=int)
    ap.add_argument('--mode', choices=['test', 'release'])
    ap.add_argument('--no-vendor', action='store_true')
    ap.add_argument('--manifest', help='update-manifest.json to check against the phone APK')
    a = ap.parse_args()
    if a.trial_absent:
        trial_absent(a.trial_absent)
    missing = [f'--{n}' for n in ('phone', 'wear', 'release', 'mode') if getattr(a, n) is None]
    if missing:
        ap.error(f'required: {" ".join(missing)} (or --trial-absent PHONE_APK alone)')
    codes = {}

    up_code = int(build_gradle_value('appVersionCode'))
    expect = {'phone': up_code * 100 + a.release, 'wear': 1000000 + up_code * 100 + a.release}
    variant_dirs = {'phone': 'mobileMod', 'wear': 'wearMod'}

    for kind, apk in (('phone', a.phone), ('wear', a.wear)):
        pkg, code, name, labels = badging(apk)
        codes[kind] = code
        check(pkg == PACKAGE, f'{kind}: package {pkg}')
        check(code == expect[kind], f'{kind}: versionCode {code} (expected {expect[kind]})')
        check(name.endswith(f'-mod{a.release}'), f'{kind}: versionName {name} ends with -mod{a.release}')
        bc = buildconfig_version_code(variant_dirs[kind])
        check(bc == code, f'{kind}: BuildConfig.VERSION_CODE {bc} = APK {code}')
        wrong = {k or 'default': v for k, v in labels.items() if v != APP_NAME}
        check(labels and not wrong, f'{kind}: app label "{APP_NAME}" in {len(labels)} locales'
              + (f' (other: {wrong})' if wrong else ''))
        certs = apk_cert(apk)
        for what, digest in upstream_certs().items():
            check(digest not in certs, f'{kind}: not signed with {what}')
        if a.mode == 'release':
            ours = norm_hex(open('mod/mod-cert.sha256').read())
            check(certs == {ours}, f'{kind}: signed with our key (mod/mod-cert.sha256)')

    check_names()
    check_icon()
    check_native()
    check_watchface_name(a.wear)
    check_novalue(a.wear, a.phone)
    check_wear_components(a.wear)
    check_phone_components(a.phone)
    check_watchface_resources(a.wear)
    check_xml_drawable_constructors(a.wear)
    check_mod_components(a.phone, a.wear, a.release)
    if not a.no_vendor:
        check_vendor(a.phone)
    check_upstream_hooks()
    if a.manifest:
        check_manifest(a.manifest, a.phone, codes['phone'])
    elif a.mode == 'release':
        fail('--mode release needs --manifest (every release carries update-manifest.json)')

    print(f'\n{len(failures)} check(s) failed' if failures else '\nAll checks passed')
    sys.exit(1 if failures else 0)


if __name__ == '__main__':
    main()
