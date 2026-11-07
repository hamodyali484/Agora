#!/usr/bin/env bash
# Keeps the newest <keep> GitHub Releases whose tag matches <regex> and deletes the rest.
# Per-run tags (v1.0.<run_number>, debug-v1.0.<n>, reports-<n>) would otherwise pile up
# forever on the releases page, so every workflow that publishes one prunes after publishing.
#
# Release only: the git tag is kept, so a pruned build can still be identified (and its
# release recreated) from the tag. Requires GH_TOKEN with contents: write.
#
# usage: prune-releases.sh '<tag-regex>' [keep=5]
set -euo pipefail

pattern=${1:?usage: prune-releases.sh <tag-regex> [keep]}
keep=${2:-5}
repo=${GITHUB_REPOSITORY:?GITHUB_REPOSITORY is not set}

mapfile -t tags < <(
  gh api "repos/$repo/releases?per_page=100" --paginate \
    -q '.[] | "\(.created_at)\t\(.tag_name)"' \
    | sort -r \
    | cut -f2 \
    | grep -E "$pattern" || true
)

echo "matched ${#tags[@]} release(s) for /$pattern/, keeping the newest $keep"
kept=0
for tag in "${tags[@]:$keep}"; do
  [ -n "$tag" ] || continue
  echo "pruning release: $tag"
  gh release delete "$tag" --yes -R "$repo"
  kept=$((kept + 1))
done
echo "pruned $kept release(s)"
