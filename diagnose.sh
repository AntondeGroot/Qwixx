#!/bin/bash
SSH="ssh -i ~/.ssh/pi_deploy_key my-pi-ext"

echo "=== systemd service status ==="
$SSH "systemctl status qwixx --no-pager" 2>&1 || true

echo ""
echo "=== last 50 log lines ==="
$SSH "journalctl -u qwixx -n 50 --no-pager" 2>&1 || true

echo ""
echo "=== recent errors (newest first) ==="
$SSH "journalctl -u qwixx -n 5000 --no-pager -o cat | awk '
  /Z[[:space:]]+(ERROR|WARN)[[:space:]]+[0-9]+/ { errs[++n] = NR }
  { L[NR] = \$0 }
  END {
    if (n == 0) { print \"(no ERROR/WARN entries in the last 5000 log lines)\"; exit }
    start = (n > 5 ? n - 4 : 1)
    k = 0
    for (i = n; i >= start; i--) {
      printf \"\n--- error %d of %d (most recent first) ---\n\", ++k, (n < 5 ? n : 5)
      print L[errs[i]]
      for (j = errs[i] + 1; j <= NR && j <= errs[i] + 20; j++) {
        if (L[j] ~ /^[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T/) break
        print L[j]
      }
    }
  }
'" 2>&1 || true

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