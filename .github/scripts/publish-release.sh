#!/usr/bin/env bash
# Publish the GitHub Release for RELEASE_TAG from an assets directory, so that the
# same step can be re-run at any point after a failure and converge on one outcome.
#
# This repository has immutable releases: once a release is published, no asset can be
# added, replaced or removed, and the release cannot be deleted. The only mutable state
# is a draft, so the sequence is: find or create a draft, make its assets equal to the
# local directory (name by name, digest by digest), and only then publish it. A re-run
# that finds the release already published does not upload anything: it verifies that
# the published assets are exactly the local ones and succeeds without touching the
# release, or fails with a message saying the tag cannot be repaired.
#
# The publish request itself may be ambiguous (GitHub publishes, the client times out),
# so its result is never trusted: the release state is read back afterwards and decides.
#
#   usage: publish-release.sh <assets-dir>
#          publish-release.sh --self-test
#   env:   RELEASE_TAG, RELEASE_VERSION, PRERELEASE (true|false), GH_REPO (owner/name),
#          GH_TOKEN (for gh)
set -euo pipefail

log() { echo "publish-release: $*" >&2; }
die() { log "$*"; exit 1; }

# --- state ---------------------------------------------------------------------------

# Prints every release whose tag is RELEASE_TAG, drafts included: "<id> <draft> <prerelease>".
matching_releases() {
  gh api --paginate "repos/${GH_REPO}/releases?per_page=100" \
    | jq -r --arg tag "${RELEASE_TAG}" \
        '.[] | select(.tag_name == $tag) | "\(.id) \(.draft) \(.prerelease)"'
}

# Sets RELEASE_ID / RELEASE_DRAFT / RELEASE_PRERELEASE from the one release that owns the
# tag, or leaves RELEASE_ID empty. A published release wins over a stale draft; two drafts
# for one tag is a state this script never creates, so it stops rather than guess.
read_release() {
  RELEASE_ID= RELEASE_DRAFT= RELEASE_PRERELEASE=
  local published=() drafts=() line
  while read -r line; do
    [[ -n "${line}" ]] || continue
    if [[ "${line}" == *" false "* ]]; then published+=("${line}"); else drafts+=("${line}"); fi
  done < <(matching_releases)
  if (( ${#published[@]} > 1 )); then die "${#published[@]} published releases carry tag ${RELEASE_TAG}"; fi
  if (( ${#published[@]} == 1 )); then
    read -r RELEASE_ID RELEASE_DRAFT RELEASE_PRERELEASE <<<"${published[0]}"
    return
  fi
  if (( ${#drafts[@]} > 1 )); then die "${#drafts[@]} draft releases carry tag ${RELEASE_TAG}; delete all but one and re-run"; fi
  if (( ${#drafts[@]} == 1 )); then read -r RELEASE_ID RELEASE_DRAFT RELEASE_PRERELEASE <<<"${drafts[0]}"; fi
}

# "name sha256" per local asset, sorted by name.
local_manifest() {
  (cd "${ASSETS}" && find . -maxdepth 1 -type f -printf '%P\n' | LC_ALL=C sort \
    | while read -r name; do printf '%s %s\n' "${name}" "$(sha256sum -- "${name}" | cut -d' ' -f1)"; done)
}

# "name sha256" per remote asset of RELEASE_ID, sorted by name. The API reports a
# sha256 digest for assets uploaded since 2025; an asset without one is downloaded
# and hashed, so the comparison is always by content.
remote_manifest() {
  local scratch; scratch=$(mktemp -d)
  gh api "repos/${GH_REPO}/releases/${RELEASE_ID}/assets?per_page=100" \
    | jq -r '.[] | "\(.name) \(.digest // "")"' | LC_ALL=C sort \
    | while read -r name digest; do
        if [[ "${digest}" == sha256:* ]]; then
          printf '%s %s\n' "${name}" "${digest#sha256:}"
        else
          gh release download "${RELEASE_TAG}" -p "${name}" -D "${scratch}" --clobber >&2
          printf '%s %s\n' "${name}" "$(sha256sum -- "${scratch}/${name}" | cut -d' ' -f1)"
        fi
      done
  rm -rf "${scratch}"
}

# Fails unless the remote assets are exactly the local ones.
verify_assets() {
  local expected actual
  expected=$(local_manifest)
  actual=$(remote_manifest)
  if [[ "${expected}" != "${actual}" ]]; then
    log "release assets differ from ${ASSETS}:"
    diff <(echo "${expected}") <(echo "${actual}") >&2 || true
    return 1
  fi
  log "$(echo "${expected}" | wc -l) assets verified by name and sha256"
}

# --- steps ---------------------------------------------------------------------------

prerelease_flag() { if [[ "${PRERELEASE}" == true ]]; then echo --prerelease; fi; }

create_draft() {
  log "creating draft release ${RELEASE_TAG} with $(ls "${ASSETS}" | wc -l) assets"
  # shellcheck disable=SC2046
  gh release create "${RELEASE_TAG}" --draft --verify-tag --title "Release ${RELEASE_TAG}" \
    --generate-notes $(prerelease_flag) -- "${ASSETS}"/*
}

# Makes the draft's assets equal to the local directory: uploads what is missing or
# differs, removes what is not local. Drafts are mutable, so this is safe to repeat.
reconcile_draft() {
  local expected actual name digest
  expected=$(local_manifest)
  actual=$(remote_manifest)
  while read -r name digest; do
    [[ -n "${name}" ]] || continue
    if ! grep -qxF "${name} ${digest}" <<<"${actual}"; then
      log "uploading ${name}"
      gh release upload "${RELEASE_TAG}" --clobber -- "${ASSETS}/${name}"
    fi
  done <<<"${expected}"
  while read -r name digest; do
    [[ -n "${name}" ]] || continue
    if ! grep -q "^${name} " <<<"${expected}"; then
      log "removing ${name}, which is not a local asset"
      gh release delete-asset "${RELEASE_TAG}" "${name}" -y
    fi
  done <<<"${actual}"
}

# Publishes the draft and reads the state back, whatever the request reported.
publish_draft() {
  log "publishing release ${RELEASE_TAG} (prerelease=${PRERELEASE})"
  if ! gh api -X PATCH "repos/${GH_REPO}/releases/${RELEASE_ID}" \
        -F draft=false -F "prerelease=${PRERELEASE}" >/dev/null; then
    log "the publish request did not return success; reading the release state back"
  fi
  read_release
  [[ -n "${RELEASE_ID}" && "${RELEASE_DRAFT}" == false ]] || die "release ${RELEASE_TAG} is still a draft"
}

check_published() {
  [[ "${RELEASE_PRERELEASE}" == "${PRERELEASE}" ]] \
    || die "release ${RELEASE_TAG} is published with prerelease=${RELEASE_PRERELEASE}, tag parses as prerelease=${PRERELEASE}"
  verify_assets || die "release ${RELEASE_TAG} is published and immutable with different assets; it cannot be repaired, release a new tag"
}

main() {
  : "${RELEASE_TAG:?}" "${RELEASE_VERSION:?}" "${PRERELEASE:?}" "${GH_REPO:?}"
  ASSETS=$1
  [[ -d "${ASSETS}" ]] || die "no assets directory ${ASSETS}"
  [[ -n "$(ls -A "${ASSETS}")" ]] || die "no assets in ${ASSETS}"
  read_release
  if [[ -n "${RELEASE_ID}" && "${RELEASE_DRAFT}" == false ]]; then
    log "release ${RELEASE_TAG} is already published; verifying it instead of uploading"
    check_published
    echo "already-published"
    return
  fi
  if [[ -z "${RELEASE_ID}" ]]; then
    create_draft
    read_release
    [[ -n "${RELEASE_ID}" ]] || die "draft release ${RELEASE_TAG} was not created"
  else
    log "draft release ${RELEASE_TAG} exists; reconciling its assets"
  fi
  reconcile_draft
  verify_assets || die "draft assets still differ after reconciliation"
  publish_draft
  check_published
  echo "published"
}

# --- self-test against a stub gh ------------------------------------------------------
# The stub keeps releases and assets in a directory and answers only the calls above.

self_test() {
  # Not `local`: the EXIT trap runs after this function's locals are gone, and under
  # `set -u` an unbound name there fails the script after every scenario has passed.
  SELF_TEST_ROOT=$(mktemp -d); local root=${SELF_TEST_ROOT}
  trap 'rm -rf -- "${SELF_TEST_ROOT}"' EXIT
  export RELEASE_TAG=v9.9.9-rc.1 RELEASE_VERSION=9.9.9-rc.1 PRERELEASE=true GH_REPO=acme/widgets
  export STUB=${root}/stub
  mkdir -p "${STUB}/bin" "${root}/assets"
  cat > "${STUB}/bin/gh" <<'STUB'
#!/usr/bin/env bash
# Stub gh: state in $STUB/releases.json ([{id,tag_name,draft,prerelease,assets:[{name,digest}]}])
# and asset bytes in $STUB/assets/<id>/<name>. $STUB/fail-publish makes the PATCH report
# failure after applying it (an ambiguous publish response).
set -euo pipefail
S=${STUB}/releases.json; test -f "$S" || echo '[]' > "$S"
echo "gh $*" >> "${STUB}/calls"
sha() { sha256sum -- "$1" | cut -d' ' -f1; }
case "$1 $2" in
  "api --paginate") jq -c '[.[] | del(.assets)]' "$S" ;;
  "api repos/"*)
    id=$(sed -E 's#repos/[^/]+/[^/]+/releases/([0-9]+)/assets.*#\1#' <<<"$2")
    jq -c --argjson id "$id" '.[] | select(.id == $id) | .assets' "$S" ;;
  "api -X")
    id=$(sed -E 's#repos/[^/]+/[^/]+/releases/([0-9]+)#\1#' <<<"$4")
    draft=$(sed -n 's/^draft=//p' <<<"$6"); pre=$(sed -n 's/^prerelease=//p' <<<"$8")
    jq --argjson id "$id" --argjson d "$draft" --argjson p "$pre" \
      'map(if .id == $id then .draft = $d | .prerelease = $p else . end)' "$S" > "$S.new" && mv "$S.new" "$S"
    if [ -e "${STUB}/fail-publish" ]; then rm -f "${STUB}/fail-publish"; echo "gateway timeout" >&2; exit 1; fi ;;
  "release create")
    tag=$3; pre=false; files=(); shift 3
    while [ $# -gt 0 ]; do case "$1" in --prerelease) pre=true;; --) shift; files=("$@"); break;; --title|--notes) shift;; esac; shift; done
    id=$(( $(jq 'length' "$S") + 100 ))
    jq --arg t "$tag" --argjson id "$id" --argjson p "$pre" '. + [{id:$id,tag_name:$t,draft:true,prerelease:$p,assets:[]}]' "$S" > "$S.new" && mv "$S.new" "$S"
    mkdir -p "${STUB}/assets/$id"
    for f in "${files[@]}"; do
      if [ -e "${STUB}/fail-upload-$(basename "$f")" ]; then rm -f "${STUB}/fail-upload-$(basename "$f")"; echo "upload failed" >&2; exit 1; fi
      cp "$f" "${STUB}/assets/$id/"; d=$(sha "$f"); n=$(basename "$f")
      jq --argjson id "$id" --arg n "$n" --arg d "$d" 'map(if .id == $id then .assets += [{name:$n,digest:("sha256:"+$d)}] else . end)' "$S" > "$S.new" && mv "$S.new" "$S"
    done ;;
  "release upload")
    tag=$3; f=${@: -1}; n=$(basename "$f"); d=$(sha "$f")
    id=$(jq -r --arg t "$tag" '.[] | select(.tag_name == $t and .draft) | .id' "$S")
    cp "$f" "${STUB}/assets/$id/$n"
    jq --argjson id "$id" --arg n "$n" --arg d "$d" 'map(if .id == $id then .assets = (.assets | map(select(.name != $n))) + [{name:$n,digest:null}] else . end)' "$S" > "$S.new" && mv "$S.new" "$S" ;;
  "release delete-asset")
    tag=$3; n=$4
    id=$(jq -r --arg t "$tag" '.[] | select(.tag_name == $t and .draft) | .id' "$S")
    rm -f "${STUB}/assets/$id/$n"
    jq --argjson id "$id" --arg n "$n" 'map(if .id == $id then .assets = (.assets | map(select(.name != $n))) else . end)' "$S" > "$S.new" && mv "$S.new" "$S" ;;
  "release download")
    tag=$3; n=$5; dir=$7
    id=$(jq -r --arg t "$tag" '[.[] | select(.tag_name == $t)] | sort_by(.draft) | .[0].id' "$S")
    cp "${STUB}/assets/$id/$n" "$dir/" ;;
  *) echo "stub gh: unsupported: $*" >&2; exit 64 ;;
esac
STUB
  chmod +x "${STUB}/bin/gh"
  export PATH="${STUB}/bin:${PATH}"
  local assets=${root}/assets self=$0
  printf 'jar' > "${assets}/graphite.jar"; printf 'linux' > "${assets}/graphite-9.9.9-rc.1-x86_64-unknown-linux-musl.tar.gz"
  state() { jq -c '.[] | {tag_name,draft,prerelease,assets:[.assets[].name]}' "${STUB}/releases.json"; }
  expect() { local want=$1; shift; local got; got=$("$@") || die "self-test: '$*' failed"; [[ "${got}" == "${want}" ]] || die "self-test: expected '${want}', got '${got}'"; }
  expect_fail() { if "$@" 2>/dev/null; then die "self-test: '$*' should have failed"; fi; }

  log "self-test 1: fresh tag is created as a draft, then published"
  expect published bash "${self}" "${assets}"
  [[ "$(state)" == '{"tag_name":"v9.9.9-rc.1","draft":false,"prerelease":true,"assets":["graphite-9.9.9-rc.1-x86_64-unknown-linux-musl.tar.gz","graphite.jar"]}' ]] || die "self-test 1: state $(state)"

  log "self-test 2: re-run on the published release verifies and uploads nothing"
  : > "${STUB}/calls"
  expect already-published bash "${self}" "${assets}"
  ! grep -qE 'release (create|upload|delete-asset)|api -X' "${STUB}/calls" || die "self-test 2: wrote to a published release: $(cat "${STUB}/calls")"

  log "self-test 3: a published release with different assets cannot be repaired"
  printf 'other' > "${assets}/graphite.jar"
  expect_fail bash "${self}" "${assets}"
  printf 'jar' > "${assets}/graphite.jar"
  PRERELEASE=false expect_fail bash "${self}" "${assets}"

  log "self-test 4: a draft left by a failed upload is reconciled and published"
  echo '[]' > "${STUB}/releases.json"; rm -rf "${STUB}/assets"
  touch "${STUB}/fail-upload-graphite.jar"
  expect_fail bash "${self}" "${assets}"
  [[ "$(state)" == *'"draft":true'* ]] || die "self-test 4: no draft after the failed upload"
  printf 'stale' > "${assets}/extra.txt"   # a stale draft asset that is no longer local
  bash "${self}" "${assets}" >/dev/null; rm -f "${assets}/extra.txt"
  echo '[]' > "${STUB}/releases.json"; rm -rf "${STUB}/assets"
  touch "${STUB}/fail-upload-graphite.jar"; expect_fail bash "${self}" "${assets}"
  id=$(jq -r '.[0].id' "${STUB}/releases.json"); mkdir -p "${STUB}/assets/${id}"
  printf 'stale' > "${STUB}/assets/${id}/extra.txt"
  jq --argjson id "${id}" 'map(if .id == $id then .assets += [{name:"extra.txt",digest:null}] else . end)' "${STUB}/releases.json" > "${STUB}/r.new" && mv "${STUB}/r.new" "${STUB}/releases.json"
  : > "${STUB}/calls"
  expect published bash "${self}" "${assets}"
  grep -q 'release delete-asset v9.9.9-rc.1 extra.txt' "${STUB}/calls" || die "self-test 4: stale asset kept"
  ! grep -q 'release create' "${STUB}/calls" || die "self-test 4: created a second release"
  [[ "$(state)" == '{"tag_name":"v9.9.9-rc.1","draft":false,"prerelease":true,"assets":["graphite-9.9.9-rc.1-x86_64-unknown-linux-musl.tar.gz","graphite.jar"]}' ]] || die "self-test 4: state $(state)"

  log "self-test 5: an ambiguous publish response is resolved by reading the state back"
  echo '[]' > "${STUB}/releases.json"; rm -rf "${STUB}/assets"
  touch "${STUB}/fail-publish"
  expect published bash "${self}" "${assets}"
  [[ "$(state)" == *'"draft":false'* ]] || die "self-test 5: state $(state)"

  log "self-test: 5 scenarios passed"
}

if [[ "${1:-}" == --self-test ]]; then self_test; else main "${1:?assets directory}"; fi
