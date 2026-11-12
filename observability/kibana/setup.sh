#!/bin/sh
# One-shot Kibana setup: payflow-* data view + saved searches.
# Idempotent: every request uses a fixed id + overwrite.
set -e

KIBANA="${KIBANA_URL:-http://kibana:5601}"

echo "waiting for kibana..."
until curl -sf "$KIBANA/api/status" | grep -q '"level":"available"'; do
  sleep 5
done

echo "creating payflow data view..."
curl -sf -X POST "$KIBANA/api/data_views/data_view" \
  -H 'kbn-xsrf: true' -H 'Content-Type: application/json' \
  -d '{"override": true, "data_view": {"id": "payflow", "title": "payflow-*", "name": "PayFlow Logs", "timeFieldName": "@timestamp"}}' \
  > /dev/null

saved_search() {
  id="$1"; title="$2"; query="$3"; columns="$4"
  curl -sf -X POST "$KIBANA/api/saved_objects/search/$id?overwrite=true" \
    -H 'kbn-xsrf: true' -H 'Content-Type: application/json' \
    -d "{
      \"attributes\": {
        \"title\": \"$title\",
        \"columns\": $columns,
        \"sort\": [[\"@timestamp\", \"desc\"]],
        \"kibanaSavedObjectMeta\": {
          \"searchSourceJSON\": \"{\\\"query\\\":{\\\"language\\\":\\\"kuery\\\",\\\"query\\\":\\\"$query\\\"},\\\"filter\\\":[],\\\"indexRefName\\\":\\\"kibanaSavedObjectMeta.searchSourceJSON.index\\\"}\"
        }
      },
      \"references\": [{\"id\": \"payflow\", \"name\": \"kibanaSavedObjectMeta.searchSourceJSON.index\", \"type\": \"index-pattern\"}]
    }" > /dev/null
  echo "  saved search: $title"
}

saved_search payflow-errors "PayFlow - errors only" \
  "level: ERROR" \
  '["level", "logger_name", "message", "traceId"]'

saved_search payflow-by-traceid "PayFlow - by traceId (paste the id into the query)" \
  "traceId: PASTE-TRACE-ID-HERE" \
  '["level", "message", "endpoint", "status", "durationMs"]'

saved_search payflow-slow-requests "PayFlow - slow requests (>2s)" \
  "message: \\\"request completed\\\" and durationMs > 2000" \
  '["endpoint", "status", "durationMs", "traceId"]'

echo "kibana setup done."
