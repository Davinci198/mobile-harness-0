#!/bin/bash
# OmniRush gateway probe (spike/omnirush-gateway-probe).
#
# Verifies the direct-call contract Mobile-Harness would use for an
# OmniRush provider: no token-proxy, plain HTTPS from the runtime.
#
# Contract (reverse-engineered from omnirush CLI 2.4.16, token-proxy.js):
#   base:      https://omnirush.ai/omnirush/v1
#   models:    GET  {base}/models            (Bearer accessToken)
#   chat:      POST {base}/responses         (Responses API, NOT chat/completions)
#   headers:   Authorization: Bearer <accessToken from ~/.omnirush/auth.json>
#              User-Agent: omnirush            (bare product name ONLY:
#                  a versioned "omnirush/x.y" UA is refused as a lookalike)
#              X-OmniRush-Client: cli/<installed-cli-version>
#   body:      {"model":..., "store":false, "stream":true,
#               "input":[{"role":"user","content":[{"type":"input_text","text":...}]}]}
#   gateway rejects: store!=false (400), stream!=true (400),
#                    string input (400 "Input must be a list"),
#                    old CLI version (426 update_required, minimum 2.4.11).
#
# The access token rotates (refreshed by `omnirush doctor`); it is read
# from ~/.omnirush/auth.json at runtime and NEVER committed.
set -u
BASE="https://omnirush.ai/omnirush/v1"
AUTH_FILE="${HOME}/.omnirush/auth.json"
CLI_VER="$(node /data/data/com.termux/files/usr/lib/node_modules/omnirush/src/bin.js --version 2>/dev/null | grep -oP '[0-9]+\.[0-9]+\.[0-9]+' | head -1)"
CLI_VER="${CLI_VER:-2.4.16}"

TOKEN="$(python3 -c "import json; print(json.load(open('${AUTH_FILE}'))['accessToken'])" 2>/dev/null)"
if [ -z "$TOKEN" ]; then
  echo "FAIL: no accessToken in ${AUTH_FILE} (run 'omnirush login' / 'omnirush doctor')"
  exit 1
fi

echo "== GET /models =="
MODELS_RAW=$(curl -s -m 20 "${BASE}/models" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Accept: application/json" \
  -H "User-Agent: omnirush" \
  -H "X-OmniRush-Client: cli/${CLI_VER}")
if echo "$MODELS_RAW" | grep -q "update_required"; then
  echo "FAIL: gateway demands CLI >= $(echo "$MODELS_RAW" | grep -oP '"minimum":"\K[^"]+') (we sent cli/${CLI_VER}; installed CLI is outdated -> run 'omnirush update')"
  exit 1
fi
MODELS=$(echo "$MODELS_RAW" | grep -oP '"id":"\K[^"]+' | sort -u)
if [ -z "$MODELS" ]; then
  echo "FAIL: empty model list (token expired? run 'omnirush doctor')"
  exit 1
fi
echo "$MODELS"

PASS=0; FAIL=0
while IFS= read -r model; do
  CODE=$(curl -s -m 120 -N -o /dev/null -w "%{http_code}" "${BASE}/responses" \
    -H "Authorization: Bearer ${TOKEN}" \
    -H "Content-Type: application/json" \
    -H "Accept: text/event-stream" \
    -H "User-Agent: omnirush" \
    -H "X-OmniRush-Client: cli/${CLI_VER}" \
    -d "{\"model\":\"${model}\",\"store\":false,\"stream\":true,\"input\":[{\"role\":\"user\",\"content\":[{\"type\":\"input_text\",\"text\":\"Say OK\"}]}],\"max_output_tokens\":5}")
  if [ "$CODE" = "200" ]; then
    PASS=$((PASS+1)); echo "OK   $model"
  else
    FAIL=$((FAIL+1)); echo "FAIL $model (HTTP $CODE)"
  fi
done <<< "$MODELS"

echo "== $PASS OK / $FAIL FAIL =="
[ "$FAIL" -eq 0 ]
