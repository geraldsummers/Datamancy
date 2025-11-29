#!/usr/bin/env bash
set -euo pipefail

WRAPPER_PATH=/usr/local/bin/stackops-wrapper

echo "[stackops] Installing forced-command wrapper at ${WRAPPER_PATH}"

tmpfile=$(mktemp)
cat >"${tmpfile}" <<'BASH'
#!/usr/bin/env bash
set -euo pipefail

# Minimal, explicit allowlist; expand carefully.
ALLOWED_CMDS=(
  "docker ps"
  "docker logs"
  "docker restart vllm"
  "docker restart litellm"
  "docker restart authelia"
  "docker restart caddy"
)

REQ="${SSH_ORIGINAL_COMMAND:-}"
if [[ -z "${REQ}" ]]; then
  echo "No command provided" >&2; exit 1
fi

# Normalize: collapse multiple spaces and trim
NORM=$(echo "$REQ" | tr -s ' ' | sed 's/[[:space:]]*$//')

# Disallow metacharacters (pipes/redirection/subshell/etc.)
if echo "$NORM" | grep -E '([|;&<>`]|\$\(|\)\()' >/dev/null; then
  echo "Disallowed metacharacters" >&2; exit 2
fi

ok=false
for prefix in "${ALLOWED_CMDS[@]}"; do
  case "$NORM" in
    ${prefix}*) ok=true; break;;
  esac
done

if ! $ok; then
  echo "Command not allowed: $NORM" >&2; exit 3
fi

export PATH=/usr/bin:/bin:/usr/local/bin
umask 077
exec bash -lc -- "$NORM"
BASH

sudo install -o root -g root -m 0755 "${tmpfile}" "${WRAPPER_PATH}"
rm -f "${tmpfile}"
echo "[stackops] Wrapper installed at ${WRAPPER_PATH}"

echo "[stackops] To bind the key to the wrapper, prepend this to the authorized key line:"
cat <<'EOAUTH'
command="/usr/local/bin/stackops-wrapper",no-agent-forwarding,no-port-forwarding,no-pty,no-user-rc,no-X11-forwarding <YOUR-PUBLIC-KEY>
EOAUTH
