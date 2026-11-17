#!/usr/bin/env bash
# Run on a trusted computer with JDK 17 + authenticated GitHub CLI; never paste keys into chat.
set -euo pipefail
umask 077
repo=iht1226/A1-A
keystore=${1:?Usage: configure-signing.sh /private/backup/sleep-alarm.p12}
command -v gh >/dev/null
command -v keytool >/dev/null
gh auth status
read -r -s -p 'Keystore password (8+ chars; stored in GitHub Secrets): ' SIGNING_STORE_PASSWORD
printf '\n'
[[ ${#SIGNING_STORE_PASSWORD} -ge 8 ]] || { printf 'Password too short\n' >&2; exit 1; }
export SIGNING_STORE_PASSWORD
SIGNING_KEY_ALIAS=sleepalarm
if [[ ! -f "$keystore" ]]; then
  mkdir -p "$(dirname "$keystore")"
  keytool -genkeypair -storetype PKCS12 -keystore "$keystore" -alias "$SIGNING_KEY_ALIAS" \
    -storepass:env SIGNING_STORE_PASSWORD -keypass:env SIGNING_STORE_PASSWORD \
    -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=Sleep Alarm Personal Updates'
fi
keytool -list -keystore "$keystore" -alias "$SIGNING_KEY_ALIAS" -storepass:env SIGNING_STORE_PASSWORD >/dev/null
base64 < "$keystore" | gh secret set ANDROID_KEYSTORE_BASE64 --repo "$repo"
printf '%s' "$SIGNING_STORE_PASSWORD" | gh secret set ANDROID_KEYSTORE_PASSWORD --repo "$repo"
printf '%s' "$SIGNING_KEY_ALIAS" | gh secret set ANDROID_KEY_ALIAS --repo "$repo"
printf '%s' "$SIGNING_STORE_PASSWORD" | gh secret set ANDROID_KEY_PASSWORD --repo "$repo"
unset SIGNING_STORE_PASSWORD
printf 'Secrets configured. Back up the private keystore and password offline. Run the Actions workflow again.\n'
