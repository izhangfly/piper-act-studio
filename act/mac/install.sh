#!/usr/bin/env bash
# Install the ACT training worker on this Mac as the launchd agent com.nervus.act-worker.
#
#   act/mac/install.sh                    install (or update) and start it
#   act/mac/install.sh --python PATH      use this Python (it must have LeRobot 0.4.4)
#   act/mac/install.sh --uninstall        stop and remove the agent (the work folder stays)
#
# The worker runs from this checkout, so `git pull` plus `launchctl kickstart -k gui/$UID/com.nervus.act-worker`
# updates it. It watches ~/Shared/NervusACT (the folder Syncthing shares with the Dell) and keeps its own
# files in ~/NervusACT-work.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LABEL=com.nervus.act-worker
PLIST="$HOME/Library/LaunchAgents/$LABEL.plist"
WORK="$HOME/NervusACT-work"
PY=""
case "${1:-}" in
	--uninstall)
		launchctl bootout "gui/$UID/$LABEL" 2>/dev/null || true
		rm -f "$PLIST"
		echo "removed $LABEL (the work folder $WORK is kept)"
		exit 0 ;;
	--python) PY="${2:?--python needs a path}" ;;
esac

has_lerobot() { "$1" -c 'import lerobot, torch, sys; sys.exit(0 if lerobot.__version__ == "0.4.4" else 1)' 2>/dev/null; }
if [[ -z "$PY" ]]; then
	for cand in "$HOME/NervusOS/robots/vla/.venv/bin/python" "$HOME/.venvs/nervus-act/bin/python"; do
		if [[ -x "$cand" ]] && has_lerobot "$cand"; then PY="$cand"; break; fi
	done
fi
if [[ -z "$PY" ]]; then
	echo "creating ~/.venvs/nervus-act with LeRobot 0.4.4 (a few minutes)"
	python3.11 -m venv "$HOME/.venvs/nervus-act" 2>/dev/null || python3 -m venv "$HOME/.venvs/nervus-act"
	"$HOME/.venvs/nervus-act/bin/pip" install -q --upgrade pip
	"$HOME/.venvs/nervus-act/bin/pip" install -q "lerobot==0.4.4" opencv-python-headless
	PY="$HOME/.venvs/nervus-act/bin/python"
fi
has_lerobot "$PY" || { echo "$PY does not have LeRobot 0.4.4" >&2; exit 1; }
[[ -x "$(dirname "$PY")/lerobot-train" ]] || { echo "no lerobot-train next to $PY" >&2; exit 1; }
mkdir -p "$WORK" "$HOME/Shared/NervusACT" "$HOME/Library/LaunchAgents"

cat >"$PLIST" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>Label</key><string>$LABEL</string>
	<key>ProgramArguments</key>
	<array><string>$PY</string><string>$HERE/worker.py</string></array>
	<key>WorkingDirectory</key><string>$WORK</string>
	<key>EnvironmentVariables</key>
	<dict>
		<key>PYTHONUNBUFFERED</key><string>1</string>
		<key>NERVUS_ACT_SHARED</key><string>$HOME/Shared/NervusACT</string>
		<key>NERVUS_ACT_WORK</key><string>$WORK</string>
		<key>PATH</key><string>$(dirname "$PY"):/usr/local/bin:/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin</string>
	</dict>
	<key>RunAtLoad</key><true/>
	<key>KeepAlive</key><true/>
	<key>ThrottleInterval</key><integer>30</integer>
	<!-- Interactive: launchd otherwise applies background resource limits (CPU and I/O throttling, efficiency
	     cores), which makes training crawl. The supervisor's caffeinate keeps the Mac awake while it trains. -->
	<key>ProcessType</key><string>Interactive</string>
	<key>ExitTimeOut</key><integer>30</integer>
	<key>StandardOutPath</key><string>$WORK/worker.out.log</string>
	<key>StandardErrorPath</key><string>$WORK/worker.out.log</string>
</dict>
</plist>
EOF
launchctl bootout "gui/$UID/$LABEL" 2>/dev/null || true
launchctl bootstrap "gui/$UID" "$PLIST"
sleep 2
if launchctl print "gui/$UID/$LABEL" 2>/dev/null | grep -q "state = running"; then
	echo "$LABEL is running ($PY)"
	echo "  log:     tail -f $WORK/worker.log"
	echo "  restart: launchctl kickstart -k gui/$UID/$LABEL"
else
	echo "$LABEL did not start; see $WORK/worker.out.log" >&2
	exit 1
fi
