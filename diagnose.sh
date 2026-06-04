#!/bin/bash
SSH="ssh -i ~/.ssh/pi_deploy_key my-pi-ext"

echo "=== systemd service status ==="
$SSH "systemctl status qwixx --no-pager" 2>&1 || true

echo ""
echo "=== last 50 log lines ==="
$SSH "journalctl -u qwixx -n 50 --no-pager" 2>&1 || true

echo ""
echo "=== errors in journal ==="
$SSH "journalctl -u qwixx --no-pager -r -o cat | grep -E -m 5 -B 5 'Z[[:space:]]+(ERROR|WARN)[[:space:]]+[0-9]+' | tac | awk 'NR==1{n=1; printf \"--- error %d ---\n\", n} /^--\$/{n++; printf \"\n--- error %d ---\n\", n; next} /Z[[:space:]]+(ERROR|WARN)/{print \">>> \" \$0; next} {print}'" 2>&1 || true

echo ""
echo "=== jar file ==="
$SSH "ls -lh /opt/qwixx/qwixx.jar" 2>&1 || true

echo ""
echo "=== override config ==="
$SSH "cat /opt/qwixx/application-override.yaml" 2>&1 || true

echo ""
echo "=== java version ==="
$SSH "java -version" 2>&1 || true

echo ""
echo "=== all listening ports and programs ==="
$SSH "sudo ss -tlnp" 2>&1 || true

echo ""
echo "=== disk space ==="
$SSH "df -h /opt" 2>&1 || true

echo ""
echo "=== memory ==="
$SSH "free -h" 2>&1 || true