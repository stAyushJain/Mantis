#!/usr/bin/env bash
# Build and push the Mantis Docker image manually.
# Use this for one-off releases or when you don't have a CI pipeline set up.
#
# Usage:
#   ./release.sh                          # builds & tags as :latest, doesn't push
#   ./release.sh --push                   # also pushes to $MANTIS_REGISTRY
#   ./release.sh --push --tag v0.3.0      # pushes :v0.3.0 alongside :latest
#
# Required env vars when pushing:
#   MANTIS_REGISTRY   e.g. ghcr.io/<user>/mantis  or  registry.example.com/team/mantis

set -euo pipefail

PUSH=0
TAG=""

while [ $# -gt 0 ]; do
    case "$1" in
        --push) PUSH=1; shift;;
        --tag)  TAG="$2"; shift 2;;
        -h|--help)
            sed -n '2,12p' "$0"; exit 0;;
        *) echo "Unknown flag: $1"; exit 1;;
    esac
done

cd "$(dirname "$0")"

if [ "$PUSH" = "1" ] && [ -z "${MANTIS_REGISTRY:-}" ]; then
    echo "MANTIS_REGISTRY must be set when --push is used."
    echo "Example:"
    echo "  export MANTIS_REGISTRY=ghcr.io/<your-user>/mantis"
    echo "  ./release.sh --push"
    exit 1
fi

LOCAL_IMAGE="mantis:latest"

echo "[release] building $LOCAL_IMAGE..."
docker build --pull --tag "$LOCAL_IMAGE" .

if [ "$PUSH" = "1" ]; then
    REMOTE_LATEST="${MANTIS_REGISTRY}:latest"
    echo "[release] tagging $REMOTE_LATEST"
    docker tag "$LOCAL_IMAGE" "$REMOTE_LATEST"
    echo "[release] pushing $REMOTE_LATEST"
    docker push "$REMOTE_LATEST"

    if [ -n "$TAG" ]; then
        REMOTE_TAG="${MANTIS_REGISTRY}:${TAG}"
        echo "[release] tagging $REMOTE_TAG"
        docker tag "$LOCAL_IMAGE" "$REMOTE_TAG"
        echo "[release] pushing $REMOTE_TAG"
        docker push "$REMOTE_TAG"
    fi
fi

echo "[release] done. Image: $LOCAL_IMAGE"
docker images --filter "reference=mantis" --format "table {{.Repository}}:{{.Tag}}\t{{.Size}}\t{{.CreatedSince}}"
