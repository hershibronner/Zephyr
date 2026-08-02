#!/bin/bash
# Double-click this file to run Zephyr on your own machine.
#
# macOS may refuse the first time with "cannot be opened because it is from an unidentified
# developer" — right-click the file and choose Open instead, then click Open in the dialog.

cd "$(dirname "$0")" || exit 1

echo ""
echo "  Starting Zephyr…"
echo ""

# Installers put node in a few different places, and a double-clicked script doesn't always inherit
# the PATH a Terminal window would.
NODE=""
for candidate in \
  "$(command -v node 2>/dev/null)" \
  /usr/local/bin/node \
  /opt/homebrew/bin/node \
  /usr/bin/node \
  "$HOME/.nvm/versions/node/"*/bin/node
do
  if [ -x "$candidate" ]; then NODE="$candidate"; break; fi
done

if [ -z "$NODE" ]; then
  echo "  Node.js isn't installed yet — Zephyr needs it to run a local server."
  echo ""
  echo "  Opening the download page. Get the button marked LTS, run the installer,"
  echo "  then double-click this file again."
  echo ""
  open "https://nodejs.org/en/download/prebuilt-installer" 2>/dev/null
  echo "  Press return to close this window."
  read -r _
  exit 1
fi

echo "  Using node: $NODE"
"$NODE" prototype/serve.mjs

# Keeps the window up if the server exits, so any error stays readable instead of vanishing.
echo ""
echo "  Zephyr has stopped. Press return to close this window."
read -r _
