#!/usr/bin/env bash
# JugglucoNGmod: things that must never reach this public repository (keys, tokens, passwords,
# Nightscout addresses, key files). Exit 1 and a list of findings if anything matches.
# Usage:
#   bash mod/check-secrets.sh                      staged changes: run before every commit
#   bash mod/check-secrets.sh --ours [THEIR_POINT] our own commits only: every non-merge commit of HEAD that
#                                                  THEIR_POINT (their code in HEAD, default
#                                                  `git merge-base HEAD upstream/main`) does not have. Upstream
#                                                  commits are not ours and are never scanned: in a sync branch
#                                                  main..HEAD would hold all their new commits (plan of stage 2, A3).
#                                                  MOD_SECRETS_HEAD=<commit> checks that commit instead of HEAD.
#   bash mod/check-secrets.sh --self-test          the e-mail rule on fixed lines (Kotlin labels against addresses)
# Personal deny list: the owner's own Nightscout address, serial numbers and the like, which no pattern can know.
# It lives OUTSIDE this repository - ../secrets-deny.txt next to it (the owner's private folder) or the file named
# by MOD_SECRETS_DENY - and only this check on the owner's computer reads it; GitHub Actions has no such file.
# One entry per line, at least 6 characters (bytes), case of Latin letters ignored; a line starting with '#' is a
# note. Entries are added
# by mod/secrets-deny.py, which never shows them. A hit names the entry number and the file, never the value.
# With --ours our commit messages (merges too: they carry PR titles) are checked against it as well.
set -euo pipefail
cd "$(dirname "$0")/.."

status=0
patterns=(
  'gh[pousr]_[A-Za-z0-9]{30,}'
  'github_pat_[A-Za-z0-9_]{30,}'
  '-----BEGIN [A-Z ]*PRIVATE KEY-----'
  'AKIA[0-9A-Z]{16}'
  '(thepassword|thekeypassword|storePassword|keyPassword|api[_-]?secret|apiSecret|API_SECRET)["'"'"']?[[:space:]]*[=:][[:space:]]*["'"'"']?[^"'"'"'[:space:]$]{4,}'
  # quoted value without "=" (Gradle: storePassword "x"); no generic password/token names: settings keys such
  # as PREF_TOKEN = "api_glucose_source_token" are normal code (62 lines in upstream, 15.11)
  '(thepassword|thekeypassword|storePassword|keyPassword|api[_-]?secret|apiSecret|API_SECRET)["'"'"']?[[:space:]]*[=:]?[[:space:]]*["'"'"'][^"'"'"'[:space:]$]{6,}["'"'"']'
  'https?://[A-Za-z0-9.-]+\.(herokuapp\.com|fly\.dev|ns\.10be\.de|nightscoutpro\.com|t1pal\.com)'
  '[?&]token=[A-Za-z0-9-]{8,}'
  # a signed token (JWT: header.payload.signature); tests build their JWTs from parts, never as one literal
  'eyJ[A-Za-z0-9_-]{15,}\.[A-Za-z0-9_-]{15,}\.'
  # an e-mail address; example.com, example.org and GitHub no-reply addresses are allowed (email_ok below)
  '[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}'
  # a path in a Windows home folder (a user name), as written in any file or string
  '[A-Za-z]:[\\/]+[Uu][Ss][Ee][Rr][Ss]([^A-Za-z0-9]|$)'
  # the owner's folder of raw cloud answers (CareLink): its name means a copied answer
  'carelink[-]probe'
)
email_re='[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}'
# allowed addresses, removed from a hit line before it is checked again (a domain that only starts the same,
# example.com.other.net, is not allowed); any letter case, as domains are; \3 = what follows the address
email_ok='[A-Za-z0-9._%+-]+@([Ee][Xx][Aa][Mm][Pp][Ll][Ee]\.([Cc][Oo][Mm]|[Oo][Rr][Gg])|[Uu][Ss][Ee][Rr][Ss]\.[Nn][Oo][Rr][Ee][Pp][Ll][Yy]\.[Gg][Ii][Tt][Hh][Uu][Bb]\.[Cc][Oo][Mm])(\.?([^A-Za-z0-9.-]|$))'
# Kotlin labels and a qualified this are not addresses: removed from a hit line before it is checked again, and only
# in the shapes Kotlin writes them, so that addresses like super(at)corp.ru, return(at)mail.ru or my-this(at)gmail.com
# stay findings:
# - a qualified this/super names a class, which starts with a capital letter: this@MainActivity.run, super@Base.f()
# - a jump label is never followed by a dot: return@forEach, break@outer, continue@loop
# Left boundary: line start, the "N:+" grep puts before an added line, or a character no address holds.
# \1 = the boundary, \3 = what follows a jump label
kotlin_this='(^|[0-9]:[+]|[^A-Za-z0-9_.%+-])(this|super)@[A-Z][A-Za-z0-9_]*'
kotlin_jump='(^|[0-9]:[+]|[^A-Za-z0-9_.%+-])(return|break|continue)@[A-Za-z_][A-Za-z0-9_]*([^A-Za-z0-9_.@-]|$)'

# stdin = lines with an e-mail hit -> stdout = those still holding a not allowed address
email_left() {
  sed -E -e "s#$kotlin_this#\\1#g" -e "s#$kotlin_jump#\\1\\3#g" -e "s#$email_ok#\\3#g" | grep -E -- "$email_re" || true
}

if [ "${1:-}" = "--self-test" ]; then
  # the e-mail rule on fixed lines: every line of `must` is a finding, no line of `clean` is; `must` is written
  # with (at) so this file holds no address the scan above would report
  must=$(sed 's/(at)/@/g' <<'LINES'
super(at)corp.ru
return(at)mail.ru
my-this(at)gmail.com
a+return(at)gmail.com
this(at)x.org
mail me: john.doe(at)gmail.com
a(at)example.com.evil.net
5:+break(at)outer.example.net
LINES
)
  clean='this@MainActivity.run { }
val c = this@ModCareLinkStarter.context
super@Base.foo()
list.forEach { return@forEach }
break@outer; continue@loop
x = "a@Example.COM" + "b@example.org"
user@users.noreply.github.com
12:+    this@CareLinkLoginActivity.finish()
7:+return@launch'
  bad=0
  while IFS= read -r l; do
    [ -n "$(printf '%s\n' "$l" | email_left)" ] || { echo "FAIL  self-test: not found: $l"; bad=1; }
  done <<< "$must"
  while IFS= read -r l; do
    [ -z "$(printf '%s\n' "$l" | email_left)" ] || { echo "FAIL  self-test: false finding: $l"; bad=1; }
  done <<< "$clean"
  [ $bad -eq 0 ] && echo "OK    self-test: e-mail rule"
  exit $bad
fi

# ---------- personal deny list ----------
deny_file="${MOD_SECRETS_DENY:-../secrets-deny.txt}"
# entry rules, the same in mod/secrets-deny.py: a BOM dropped; line trimmed of spaces and tabs; empty lines and
# '#' lines skipped; numbered from 1. awk runs with LC_ALL=C: bytes, the same in every locale.
deny_clean='if (FNR == 1) sub(/^\357\273\277/, ""); sub(/\r$/, ""); gsub(/^[ \t]+|[ \t]+$/, "")
  if ($0 == "" || substr($0, 1, 1) == "#") next'
# masks every entry in a text line with <entry K> (fixed strings, case ignored); e[1..n] = entries, lower case
deny_mask_fn='function mask(s,   i, p, t, out) {
    for (i = 1; i <= n; i++) {
      out = ""; t = tolower(s)
      while ((p = index(t, e[i])) > 0) {
        out = out substr(s, 1, p - 1) "<entry " i ">"
        s = substr(s, p + length(e[i])); t = substr(t, p + length(e[i]))
      }
      s = out s
    }
    return s
  }'
deny_n=0
if [ -f "$deny_file" ]; then
  read -r deny_n deny_short < <(LC_ALL=C awk '{ '"$deny_clean"'; n++; if (length($0) < 6) s++ }
    END { print n + 0, s + 0 }' "$deny_file")
  [ "$deny_short" = 0 ] || {
    echo "FAIL  personal deny list: $deny_short entry(ies) shorter than 6 characters would match almost anything" \
      "(python -X utf8 mod/secrets-deny.py list)"; exit 1; }
  deny_note="personal deny list: $deny_n entry(ies)"
elif [ -n "${MOD_SECRETS_DENY:-}" ]; then
  echo "FAIL  personal deny list not found: MOD_SECRETS_DENY=$MOD_SECRETS_DENY"; exit 1
elif [ -n "${GITHUB_ACTIONS:-}" ]; then
  deny_note="personal deny list: none on GitHub (by design)"
else
  echo "WARN  personal deny list ../secrets-deny.txt not found: your Nightscout address and serial numbers are" \
    "NOT checked (python -X utf8 mod/secrets-deny.py add)"
  deny_note="personal deny list: NOT FOUND"
fi

# $1 = git command (diff / show), the rest = its arguments: added lines of every file but deleted ones; binary
# files as text (a database or an archive can hold a serial number in plain bytes)
deny_diff() {
  local cmd="$1"; shift
  git -c core.quotepath=off "$cmd" -U0 --text --no-color --no-ext-diff --no-textconv --src-prefix=a/ \
    --dst-prefix=b/ --diff-filter=d "$@"
}

# stdin -> stdout with every entry of the personal list masked (for lines this script prints)
deny_mask() {
  if [ "$deny_n" -gt 0 ]; then
    LC_ALL=C awk "$deny_mask_fn"' NR == FNR { '"$deny_clean"'; e[++n] = tolower($0); next } { print mask($0) }' \
      "$deny_file" -
  else
    cat
  fi
}

# $1 = label, stdin = a diff from deny_diff; prints the entry number and the file name (entries in it masked),
# never the value
deny_scan() {
  local label="$1" i where
  [ "$deny_n" -gt 0 ] || return 0
  while IFS=$'\t' read -r i where; do
    echo "FAIL  personal deny list entry $i ($label): $where"
    status=1
  done < <(LC_ALL=C awk "$deny_mask_fn"'
    NR == FNR { '"$deny_clean"'; e[++n] = tolower($0); next }
    function check(s, where,   i, t) {
      t = tolower(s)
      for (i = 1; i <= n; i++)
        if (index(t, e[i]) && !((i SUBSEP where) in seen)) { seen[i SUBSEP where] = 1; print i "\t" mask(where) }
    }
    # header of a file: "diff --git", extended lines, "--- a/x", "+++ b/x" until the first "@@"
    /^diff --git / {
      hdr = 1; f = $0; sub(/^diff --git a\/.* b\//, "", f)
      if (f != "(commit message)") check(f, f " (file name)")
      next
    }
    /^@@/ { hdr = 0; next }
    hdr { next }
    /^\+/ { check(substr($0, 2), f) }
  ' "$deny_file" -)
}

# printed lines hide every pattern match, not only the one reported (a line may hold several), and a token whole
mask_all=(-e 's#([?&]token=)[^&[:space:]"'"'"']+#\1<hidden>#g')
for p in "${patterns[@]}"; do mask_all+=(-e "s#$p#<hidden: pattern>#g"); done

# $1 = label, stdin = list of file names, $2 = added lines
scan() {
  local label="$1" added="$2" f p hits
  while IFS= read -r f; do
    case "$f" in
      *.jks|*.keystore|*.p12|*.pem|*.key|local.properties|*.env)
        echo "FAIL  key or secret file ($label): $(printf '%s\n' "$f" | deny_mask)"; status=1 ;;
    esac
  done
  for p in "${patterns[@]}"; do
    hits=$(printf '%s\n' "$added" | grep -nE -- "$p" || true)
    # Upstream's public test key is known and allowed in their own files only; ours never use it.
    hits=$(printf '%s\n' "$hits" | grep -v 'IDontKnow' || true)
    if [ "$p" = "$email_re" ] && [ -n "$hits" ]; then
      hits=$(printf '%s\n' "$hits" | email_left)
    fi
    if [ -n "$hits" ]; then
      echo "FAIL  pattern ($label): $p"
      # the line for finding it, every match and personal entry masked: this output may be read by others
      { printf '%s\n' "$hits" | sed -E "${mask_all[@]}" | deny_mask | cut -c1-160; } ||
        echo "      (lines not shown: masking failed)"
      status=1
    fi
  done
}

if [ "${1:-}" = "--ours" ]; then
  point="${2:-}"
  # the commit to check: HEAD, or MOD_SECRETS_HEAD (the local pre-push lock passes the commit being pushed)
  head="${MOD_SECRETS_HEAD:-HEAD}"
  git rev-parse -q --verify "$head^{commit}" > /dev/null || { echo "FAIL  unknown commit: $head"; exit 1; }
  if [ -z "$point" ]; then
    git rev-parse -q --verify upstream/main > /dev/null || {
      echo "FAIL  upstream/main is not fetched: git fetch upstream main (or pass their point)"; exit 1; }
    point=$(git merge-base "$head" upstream/main)
  fi
  git rev-parse -q --verify "$point^{commit}" > /dev/null || { echo "FAIL  unknown point: $point"; exit 1; }
  commits=$(git rev-list --no-merges "$head" --not "$point")
  n=0
  for c in $commits; do
    n=$((n + 1))
    scan "commit ${c:0:9}" "$(git show --format= -U0 --no-color "$c" | grep -E '^\+[^+]' || true)" \
      < <(git show --format= --name-only --diff-filter=AM "$c")
    deny_scan "commit ${c:0:9}" < <(deny_diff show --format= "$c")
  done
  if [ "$deny_n" -gt 0 ]; then
    for c in $(git rev-list "$head" --not "$point"); do
      deny_scan "commit ${c:0:9}" < <(printf 'diff --git a/(commit message) b/(commit message)\n@@\n'
                                      git log -1 --format=%B "$c" | sed 's/^/+/')
    done
  fi
  # merge commits are skipped above: a conflict resolved by hand lives only there, so the whole tree against
  # their code is scanned too
  scan "tree vs their ${point:0:9}" "$(git diff -U0 --no-color "$point" "$head" | grep -E '^\+[^+]' || true)" \
    < <(git diff --name-only --diff-filter=AM "$point" "$head")
  deny_scan "tree vs their ${point:0:9}" < <(deny_diff diff "$point" "$head")
  [ $status -eq 0 ] && echo "OK    no secrets in our $n commit(s) and in the tree on top of their ${point:0:9}" \
    "($deny_note)"
  exit $status
fi

files=$(git diff --cached --name-only --diff-filter=AM)
scan staged "$(git diff --cached -U0 --no-color | grep -E '^\+[^+]' || true)" < <(printf '%s\n' "$files" | grep . || true)
deny_scan staged < <(deny_diff diff --cached)
[ $status -eq 0 ] && echo "OK    no secrets in staged changes ($(echo "$files" | grep -c . || true) files; $deny_note)"
exit $status
