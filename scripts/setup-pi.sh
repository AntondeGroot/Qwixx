#!/bin/bash
set -e

SSH="ssh -i ~/.ssh/pi_deploy_key my-pi"

echo "📁 Creating directories..."
# The service runs as ubuntu, so the log dir it writes to must be owned by ubuntu (/opt/qwixx itself
# stays root-owned — only the jar lives there, and deploy.sh installs that with sudo).
$SSH "sudo mkdir -p /opt/qwixx/logs && sudo chown ubuntu:ubuntu /opt/qwixx/logs"

echo "⚙️  Installing systemd service..."
$SSH "sudo tee /etc/systemd/system/qwixx.service > /dev/null << 'EOF'
[Unit]
Description=Qwixx game server
After=network.target

[Service]
User=ubuntu
ExecStart=/usr/bin/java -jar /opt/qwixx/qwixx.jar
Restart=on-failure

[Install]
WantedBy=multi-user.target
EOF"
$SSH "sudo systemctl daemon-reload && sudo systemctl enable qwixx"

echo "📝 Creating application override config..."
$SSH "sudo tee /opt/qwixx/application-override.yaml > /dev/null << 'EOF'
# no context-path: Qwixx serves at root, nginx strips /qwixx/ prefix before forwarding

# Activates the rotation caps packaged in application.properties (10MB/file, 100MB total).
logging:
  file:
    name: /opt/qwixx/logs/qwixx.log
EOF"

echo ""
echo "✅ Pi setup complete."
echo "   Run ./scripts/deploy.sh to deploy the application."