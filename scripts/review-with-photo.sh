#!/usr/bin/env bash
# Post a review with a photo to a running server, so the vision model can analyze it.
# Usage: scripts/review-with-photo.sh <product-id> <image.jpg|png> "<review text>" [rating]
set -euo pipefail
product="$1"; image="$2"; text="$3"; rating="${4:-2}"
case "$image" in
  *.png) type=image/png ;;
  *.webp) type=image/webp ;;
  *) type=image/jpeg ;;
esac
data=$(base64 < "$image" | tr -d '\n')
curl -sS -X POST "http://localhost:${PORT:-8080}/products/$product/reviews" \
  -H 'Content-Type: application/json' \
  -d "{\"rating\": $rating, \"title\": \"Photo review\", \"text\": \"$text\", \"photos\": [{\"mediaType\": \"$type\", \"data\": \"$data\"}]}"
echo
