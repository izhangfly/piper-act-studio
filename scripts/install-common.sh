#!/usr/bin/env bash
# Shared install steps for the PiPER desktop apps (piper-hand-teleop, piper-llm-arm, piper-act-studio).
# Sourced by each repository's install.sh, which runs as root via sudo from the desktop user's account.
set -euo pipefail
step() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
ok() { printf '    \033[32mok\033[0m %s\n' "$*"; }
warn() { printf '    \033[33m!\033[0m %s\n' "$*" >&2; }
die() { printf '\n\033[1;31minstall: %s\033[0m\n' "$*" >&2; exit 1; }

[[ $EUID -eq 0 ]] || die "run with sudo: sudo ./install.sh"
DESKTOP_USER="${SUDO_USER:-}"
[[ -n "$DESKTOP_USER" && "$DESKTOP_USER" != root ]] || die "run it with sudo from your own account"
DESKTOP_HOME="$(getent passwd "$DESKTOP_USER" | cut -d: -f6)"
DESKTOP_UID="$(id -u "$DESKTOP_USER")"
as_user() { sudo -u "$DESKTOP_USER" env HOME="$DESKTOP_HOME" "$@"; }

# The PiPER driver: the only program that touches CAN (127.0.0.1:8770, loopback only).
install_driver() {
	step "PiPER driver (127.0.0.1:8770) and CAN"
	DEBIAN_FRONTEND=noninteractive apt-get install -y -qq python3-venv can-utils iproute2 libnotify-bin >/dev/null
	id nervus-piper >/dev/null 2>&1 || useradd --system --no-create-home --home-dir /opt/nervus/piper \
		--shell /usr/sbin/nologin nervus-piper
	install -d -m 0755 /opt/nervus/piper
	install -m 0644 "$REPO/driver/piper_webctl.py" "$REPO/driver/panel.html" "$REPO/driver/README.md" /opt/nervus/piper/
	install -m 0755 "$REPO/driver/piper_control.py" /opt/nervus/piper/
	DEBIAN_FRONTEND=noninteractive apt-get install -y -qq python3-gi gir1.2-webkit2-4.1 >/dev/null
	install -D -m 0644 "$REPO/desktop/nervus-piper-control.desktop" /usr/share/applications/nervus-piper-control.desktop
	install -D -m 0644 "$REPO/desktop/nervus-piper-control.svg" /usr/share/icons/hicolor/scalable/apps/nervus-piper-control.svg
	[[ -x /opt/nervus/piper/venv/bin/python ]] || python3 -m venv /opt/nervus/piper/venv
	/opt/nervus/piper/venv/bin/pip install -q -r "$REPO/driver/requirements.txt"
	install -D -m 0755 "$REPO/driver/can-up" /usr/libexec/nervus/can-up
	install -D -m 0644 "$REPO/driver/90-nervus-can.rules" /etc/udev/rules.d/90-nervus-can.rules
	install -D -m 0644 "$REPO/driver/systemd/nervus-can.service" /etc/systemd/system/nervus-can.service
	install -D -m 0644 "$REPO/driver/systemd/nervus-piper.service" /etc/systemd/system/nervus-piper.service
	udevadm control --reload 2>/dev/null || true
	systemctl daemon-reload
	systemctl enable --now nervus-can.service >/dev/null 2>&1 || warn "can0 not up yet (plug in the CAN adapter)"
	sed "s/@USER@/$DESKTOP_USER/g" "$REPO/driver/50-piper-apps.rules.in" >/etc/polkit-1/rules.d/50-piper-apps.rules
	ok "driver installed; it starts when an app opens (systemctl start nervus-piper)"
}

# A Compose Desktop app built with its own runtime: needs JDK 21 (JAVA_HOME or /usr/lib/jvm/java-21-*).
build_app() {
	local dir="$1" name="$2"
	step "Build $name (Compose Desktop, self-contained)"
	local jh="${JAVA_HOME:-}"
	[[ -n "$jh" ]] || jh="$(ls -d /usr/lib/jvm/java-21-openjdk-* 2>/dev/null | head -1)"
	[[ -n "$jh" ]] || { DEBIAN_FRONTEND=noninteractive apt-get install -y -qq openjdk-21-jdk >/dev/null; jh="$(ls -d /usr/lib/jvm/java-21-openjdk-* | head -1)"; }
	(cd "$REPO/$dir" && as_user env JAVA_HOME="$jh" ./gradlew createDistributable --console=plain -q)
	rm -rf "/opt/nervus/apps/$name"
	install -d -m 0755 /opt/nervus/apps
	cp -r "$REPO/$dir/build/compose/binaries/main/app/$name" "/opt/nervus/apps/$name"
	ok "/opt/nervus/apps/$name"
}

# The desktop icon: nervus-standalone starts the services the app needs, then opens it.
install_launcher() {
	local entry="$1"
	step "Desktop icon: $entry"
	install -D -m 0755 "$REPO/desktop/nervus-standalone" /usr/local/bin/nervus-standalone
	install -D -m 0644 "$REPO/desktop/$entry.svg" "/usr/share/icons/hicolor/scalable/apps/$entry.svg"
	install -D -m 0644 "$REPO/desktop/$entry.desktop" "/usr/share/applications/$entry.desktop"
	gtk-update-icon-cache -q /usr/share/icons/hicolor 2>/dev/null || true
	update-desktop-database -q /usr/share/applications 2>/dev/null || true
	local desk
	desk="$(as_user xdg-user-dir DESKTOP 2>/dev/null || echo "$DESKTOP_HOME/Desktop")"
	if [[ -d "$desk" ]]; then
		install -o "$DESKTOP_USER" -g "$DESKTOP_USER" -m 0755 "$REPO/desktop/$entry.desktop" "$desk/"
		as_user env DBUS_SESSION_BUS_ADDRESS="unix:path=/run/user/$DESKTOP_UID/bus" \
			gio set "$desk/$entry.desktop" metadata::trusted true 2>/dev/null || true
	fi
	ok "in the app grid and on the desktop"
}
