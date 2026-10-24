#!/usr/bin/env bash
# Install PiPER ACT Studio on the robot's Ubuntu 24.04 machine:  sudo ./install.sh
# The Mac (or any training machine) is set up separately: act/mac/install.sh there.
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$REPO/scripts/install-common.sh"

install_driver

step "ACT engine (record, run, post-train; 127.0.0.1:8795)"
install -d -m 0755 /opt/nervus/act
rm -rf /opt/nervus/act/nervus_act
cp -r "$REPO/act/engine/nervus_act" /opt/nervus/act/
install -m 0644 "$REPO/act/README.md" /opt/nervus/act/README.md
if [[ ! -x /opt/nervus/act/venv/bin/python ]]; then
	python3 -m venv /opt/nervus/act/venv
	/opt/nervus/act/venv/bin/pip install -q --upgrade pip
	echo "    installing torch (CPU) and LeRobot: a few minutes the first time"
	/opt/nervus/act/venv/bin/pip install -q --index-url https://download.pytorch.org/whl/cpu torch==2.10.0 torchvision==0.25.0
fi
/opt/nervus/act/venv/bin/pip install -q -r "$REPO/act/engine/requirements.txt"
install -d -o "$DESKTOP_USER" -g "$DESKTOP_USER" -m 0755 "$DESKTOP_HOME/Shared" "$DESKTOP_HOME/Shared/NervusACT"
usermod -aG video "$DESKTOP_USER"
sed -e "s/@USER@/$DESKTOP_USER/g; s#@HOME@#$DESKTOP_HOME#g" -e '/^PartOf=nervud.service/d' \
	"$REPO/act/nervus-act.service.in" >/etc/systemd/system/nervus-act.service
systemctl daemon-reload
ok "started when ACT Studio opens; shared folder ~/Shared/NervusACT (sync it to the training machine)"

build_app act-studio act-studio
install_launcher nervus-act
printf '\nDone. Set up the training machine with act/mac/install.sh, sync ~/Shared between them, then open "ACT Studio".\n'
