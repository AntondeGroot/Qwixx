#!/bin/bash
set -e

SSH="ssh -i ~/.ssh/pi_deploy_key my-pi"

echo "📁 Creating directory..."
$SSH "sudo mkdir -p /opt/qwixx"

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
EOF"

echo ""
echo "✅ Pi setup complete."
echo "   Run ./scripts/deploy.sh to deploy the application."