#!/bin/bash

# Colors for output
GREEN='\033[0;32m'
RED='\033[0;31m'
NC='\033[0m'

echo -e "${GREEN}Starting Magic Mirror Orange Pi Installation...${NC}"

# 1. Install System Dependencies
echo -e "Installing system dependencies (Node.js, gpiod, NetworkManager)..."
sudo apt-get update
sudo apt-get install -y nodejs npm libgpiod-utils network-manager

# 2. Setup Polkit for NetworkManager (so Node.js can use nmcli without sudo)
echo -e "Configuring Polkit permissions for NetworkManager..."
USER_NAME=$(whoami)
POLKIT_FILE="/etc/polkit-1/localauthority/50-local.d/magic-mirror.pkla"
sudo mkdir -p /etc/polkit-1/localauthority/50-local.d/

sudo bash -c "cat > $POLKIT_FILE" <<EOF
[Magic Mirror Network Control]
Identity=unix-user:$USER_NAME
Action=org.freedesktop.NetworkManager.*
ResultAny=yes
ResultInactive=yes
ResultActive=yes
EOF

# 3. Setup Polkit for gpiod (to allow GPIO access without root)
echo -e "Configuring GPIO permissions..."
# Adding user to gpio group if it exists, otherwise we rely on udev rules provided by libgpiod
sudo usermod -aG gpio $USER_NAME 2>/dev/null || echo "GPIO group not found, skipping..."

# 4. Install Project Dependencies
echo -e "Installing Node.js project dependencies..."
npm install

# 5. Setup Systemd Service
echo -e "Setting up Systemd service..."
SERVICE_FILE="magic-mirror.service"
INSTALL_DIR=$(pwd)
USER_NAME=$(whoami)

# Replace placeholders in service file
sudo sed -i "s|%u|$USER_NAME|g" $SERVICE_FILE

sudo cp $SERVICE_FILE /etc/systemd/system/magic-mirror.service
sudo systemctl daemon-reload
sudo systemctl enable magic-mirror.service

echo -e "${GREEN}Installation complete!${NC}"
echo -e "Your server is configured to start automatically."
echo -e "Run 'sudo systemctl start magic-mirror.service' to start it now."
echo -e "Check logs with 'journalctl -u magic-mirror.service -f'"
