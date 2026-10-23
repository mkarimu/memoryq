# Memoryq: send the server code and web pages from this PC to the VPS (memoryq.com) and install them there.
# The VPS keeps its own data (accounts, chats, songs...). Run it from Vidiserver\scripts whenever there is an update.
#   -KeepOldPorts   leave the old unencrypted ports (8080, 8888, 8889) open for phones that still have an old app
#   -WithData       also send this PC's data (only used if the VPS has no data yet, e.g. a brand-new VPS)
param([switch]$KeepOldPorts, [switch]$WithData)
$__self = $PSCommandPath
& {
  $utf8 = New-Object System.Text.UTF8Encoding($false)
  $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
  $root = "C:\Users\nelso\OneDrive\Documents\Vidiserver"
  $src = "$root\src\com\vstream\Vidiserver"
  $vps = "162.253.130.252"
  $domain = "memoryq.com"
  # SSH: use Windows' own if it is there, otherwise Microsoft's portable OpenSSH from GitHub (no install needed).
  $ProgressPreference = "SilentlyContinue"
  [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
  $sshExe = $null; $scpExe = $null
  $c1 = Get-Command ssh -ErrorAction SilentlyContinue; $c2 = Get-Command scp -ErrorAction SilentlyContinue
  if ($c1 -and $c2) { $sshExe = $c1.Source; $scpExe = $c2.Source }
  if (-not $sshExe) {
    $odir = "$root\tools\openssh"
    $hit = Get-ChildItem $odir -Recurse -Filter ssh.exe -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $hit) {
      "Windows has no SSH program - downloading Microsoft's portable OpenSSH (about 5 MB)..."
      New-Item -ItemType Directory -Force $odir | Out-Null
      $zip = Join-Path $env:TEMP "OpenSSH-Win64.zip"
      foreach ($tag in "v9.8.3.0p2-Preview", "v9.5.0.0p1-Beta") {
        try {
          Invoke-WebRequest -Uri "https://github.com/PowerShell/Win32-OpenSSH/releases/download/$tag/OpenSSH-Win64.zip" -OutFile $zip -UseBasicParsing
          Expand-Archive -Path $zip -DestinationPath $odir -Force
          break
        } catch { "  could not get OpenSSH $tag ($($_.Exception.Message))" }
      }
      $hit = Get-ChildItem $odir -Recurse -Filter ssh.exe -ErrorAction SilentlyContinue | Select-Object -First 1
    }
    if (-not $hit) { throw "Could not get an SSH program. Send me the lines above. Nothing was changed." }
    $sshExe = $hit.FullName
    $scpExe = Join-Path $hit.DirectoryName "scp.exe"
    "Using SSH from $($hit.DirectoryName)"
  }

  # VPS login name (remembered for next time)
  $userFile = "$root\tools\vps-user.txt"
  $vpsUser = if (Test-Path $userFile) { ([System.IO.File]::ReadAllText($userFile)).Trim() } else { "" }
  $answer = Read-Host ("VPS username for $vps" + $(if ($vpsUser) { " [press Enter for $vpsUser]" } else { "" }))
  if ($answer.Trim()) { $vpsUser = $answer.Trim() }
  if (-not ($vpsUser -match '^[a-z_][a-z0-9_.-]{0,31}$')) { throw "That does not look like a Linux username: '$vpsUser'. Nothing was changed." }
  New-Item -ItemType Directory -Force "$root\tools" | Out-Null
  [System.IO.File]::WriteAllText($userFile, $vpsUser)


  # ================= Pack the code, web pages and data (the VPS only uses the data on its very first install) =================
  "Packing the server for the VPS..."
  $stage = Join-Path $env:TEMP ("vstream-stage-" + $stamp)
  New-Item -ItemType Directory -Force $stage | Out-Null
  Copy-Item "$root\src" $stage -Recurse -Force
  Copy-Item "$root\*.html" $stage -Force
  if ($WithData) { foreach ($f in "users.txt","claims.txt","chat.txt","news.txt","moderation.txt","calls.txt","profiles.txt","notifications.txt","ads.txt","wall.txt","radio.txt","ads","wall","profile-photos","radio-music") {
    if (Test-Path "$root\$f") { Copy-Item "$root\$f" $stage -Recurse -Force }
  } }
  $key = [Environment]::GetEnvironmentVariable("OPENAI_API_KEY", "User")
  if (-not $key) { $key = $env:OPENAI_API_KEY }
  if ($key) { [System.IO.File]::WriteAllText("$stage\vstream.env", "OPENAI_API_KEY=$key`n", $utf8); "  (your OpenAI key goes along, readable only by the server)" }
  # A standard zip made with Windows' own .NET libraries (no tar needed); paths use "/" for Linux.
  $pkg = Join-Path $env:TEMP "vstream-deploy.zip"
  Remove-Item $pkg -ErrorAction SilentlyContinue
  Add-Type -AssemblyName System.IO.Compression
  Add-Type -AssemblyName System.IO.Compression.FileSystem
  $base = (Get-Item $stage).FullName.TrimEnd('\') + '\'
  $fs = [System.IO.File]::Open($pkg, [System.IO.FileMode]::Create)
  $za = New-Object System.IO.Compression.ZipArchive($fs, [System.IO.Compression.ZipArchiveMode]::Create)
  try {
    foreach ($file in Get-ChildItem $stage -Recurse -File) {
      $rel = $file.FullName.Substring($base.Length).Replace('\', '/')
      $level = if ($file.Extension -match '^\.(mp3|m4a|aac|ogg|opus|flac|jpg|jpeg|png|webp)$') { [System.IO.Compression.CompressionLevel]::NoCompression } else { [System.IO.Compression.CompressionLevel]::Optimal }
      $entry = $za.CreateEntry($rel, $level)
      $out = $entry.Open()
      $in = [System.IO.File]::OpenRead($file.FullName)
      $in.CopyTo($out)
      $in.Dispose()
      $out.Dispose()
    }
  } finally {
    $za.Dispose()
    $fs.Dispose()
  }
  if (-not (Test-Path $pkg) -or (Get-Item $pkg).Length -lt 10000) { "PACKING FAILED. Send me the lines above."; return }
  "  package: $([math]::Round((Get-Item $pkg).Length / 1MB, 1)) MB"
  Remove-Item $stage -Recurse -Force -ErrorAction SilentlyContinue

  if (-not (Test-Path $pkg)) { "The package $pkg is missing - send me the lines above."; return }
  $sh = Join-Path $env:TEMP "vstream-install.sh"
  $script = @'
#!/bin/bash
# VStream server install / update for Ubuntu 24.04 (run as root).
#   bash vstream-install.sh memoryq.com
# First run: installs Java, FFmpeg, Caddy (HTTPS), MediaMTX, Piper (DJ voice), moves the data in, starts everything.
# Later runs: update the code only; the server's data on this VPS is kept (FORCE_DATA=1 replaces it from the package).
set -euo pipefail

DOMAIN="${1:-memoryq.com}"
APP=/opt/vstream
PKG="${PKG:-/root/vstream-deploy.zip}"
MTX_VERSION=v1.12.3
PIPER_URL=https://github.com/rhasspy/piper/releases/download/2023.11.14-2/piper_linux_x86_64.tar.gz
VOICES="en_US-lessac-medium:en/en_US/lessac/medium en_US-ryan-medium:en/en_US/ryan/medium en_US-lessac-high:en/en_US/lessac/high en_US-ryan-high:en/en_US/ryan/high"
DATA_FILES="users.txt claims.txt chat.txt news.txt moderation.txt calls.txt profiles.txt notifications.txt ads.txt wall.txt radio.txt ads wall profile-photos radio-music"

say() { echo; echo "== $*"; }
[ "$(id -u)" = 0 ] || { echo "Run this as root."; exit 1; }
[ -f "$PKG" ] || { echo "The package $PKG is missing - run deploy-vps.ps1 on the PC."; exit 1; }
PUBLIC_IP="$(hostname -I | awk '{print $1}' || true)"

say "Installing system packages (Java, FFmpeg, Caddy, firewall)..."
export DEBIAN_FRONTEND=noninteractive
apt-get update -y -q
apt-get install -y -q openjdk-21-jdk-headless ffmpeg caddy curl ufw tar unzip ca-certificates >/dev/null
java -version 2>&1 | head -1
ffmpeg -hide_banner -version | head -1

id vstream >/dev/null 2>&1 || useradd --system --home-dir "$APP" --shell /usr/sbin/nologin vstream
mkdir -p "$APP"
FIRST=0
[ -f "$APP/users.txt" ] || FIRST=1

say "Unpacking the VStream package..."
TMP="$(mktemp -d)"
case "$PKG" in
  *.zip) unzip -q -o "$PKG" -d "$TMP" ;;
  *) tar -xzf "$PKG" -C "$TMP" ;;
esac
rm -rf "$APP/src"
cp -r "$TMP/src" "$APP/src"
cp "$TMP"/*.html "$APP/"
if [ "$FIRST" = 1 ] || [ "${FORCE_DATA:-0}" = 1 ]; then
  for f in $DATA_FILES; do
    if [ -e "$TMP/$f" ]; then rm -rf "${APP:?}/$f"; cp -r "$TMP/$f" "$APP/$f"; echo "  moved in: $f"; fi
  done
else
  echo "  keeping the data already on this server (accounts, chats, songs...)"
fi
if [ -f "$TMP/vstream.env" ] && [ ! -f "$APP/vstream.env" ]; then
  install -m 600 "$TMP/vstream.env" "$APP/vstream.env"
  echo "  OpenAI key copied (AI news writing and OpenAI DJ voices)"
fi
touch "$APP/vstream.env"
chmod 600 "$APP/vstream.env"

say "Compiling the server..."
cd "$APP"
rm -rf out.new && mkdir out.new
find src -name '*.java' > /tmp/vstream-sources.txt
javac -encoding UTF-8 -nowarn -d out.new @/tmp/vstream-sources.txt
rm -rf out && mv out.new out
echo "  compiled OK"

say "DJ voice (Piper) and its voices..."
mkdir -p "$APP/tools/piper-voices"
if [ ! -x "$APP/tools/piper/piper" ]; then
  if curl -fsSL "$PIPER_URL" -o /tmp/piper.tgz && tar -xzf /tmp/piper.tgz -C "$APP/tools"; then
    echo "  Piper installed"
  else
    echo "  WARNING: could not download Piper - the DJ uses FFmpeg's simple voice"
  fi
fi
for v in $VOICES; do
  name="${v%%:*}"; path="${v#*:}"
  if [ ! -f "$APP/tools/piper-voices/$name.onnx" ]; then
    echo "  downloading voice $name..."
    curl -fsSL "https://huggingface.co/rhasspy/piper-voices/resolve/v1.0.0/$path/$name.onnx.json" -o "$APP/tools/piper-voices/$name.onnx.json" \
      && curl -fsSL "https://huggingface.co/rhasspy/piper-voices/resolve/v1.0.0/$path/$name.onnx" -o "$APP/tools/piper-voices/$name.onnx" \
      || { echo "  WARNING: could not download $name"; rm -f "$APP/tools/piper-voices/$name.onnx"*; }
  fi
done

say "MediaMTX (live video, calls)..."
mkdir -p "$APP/mediamtx"
if [ ! -x "$APP/mediamtx/mediamtx" ]; then
  curl -fsSL "https://github.com/bluenviron/mediamtx/releases/download/$MTX_VERSION/mediamtx_${MTX_VERSION}_linux_amd64.tar.gz" -o /tmp/mediamtx.tgz
  tar -xzf /tmp/mediamtx.tgz -C "$APP/mediamtx"
  cp "$APP/mediamtx/mediamtx.yml" "$APP/mediamtx/mediamtx.default.yml"
fi
YML="$APP/mediamtx/mediamtx.yml"
cp "$APP/mediamtx/mediamtx.default.yml" "$YML"
sed -i \
  -e 's|^authMethod: .*|authMethod: http|' \
  -e 's|^authHTTPAddress:.*|authHTTPAddress: http://127.0.0.1:9120/auth|' \
  -e 's|^api: .*|api: yes|' \
  -e 's|^apiAddress: .*|apiAddress: 127.0.0.1:9997|' \
  -e 's|^srt: .*|srt: no|' \
  -e "s|^webrtcAdditionalHosts: .*|webrtcAdditionalHosts: [$PUBLIC_IP]|" \
  "$YML"
grep -E '^(authMethod|authHTTPAddress|api|apiAddress|webrtcAdditionalHosts):' "$YML" | sed 's/^/  /'
# Live channels are recorded to the video disk (made by setup-disks.sh) and kept 30 days. Calls are never recorded.
if [ -d /srv/mkarimu ]; then
  mkdir -p /srv/mkarimu/recordings
  chown vstream:vstream /srv/mkarimu/recordings
  sed -i '/^paths:$/a\  "~^live/CHANNEL-[0-9]+$":\n    record: yes\n    recordPath: /srv/mkarimu/recordings/%path/%Y-%m-%d_%H-%M-%S-%f\n    recordFormat: fmp4\n    recordSegmentDuration: 1h\n    recordDeleteAfter: 720h' "$YML"
  echo "  live channels are recorded to /srv/mkarimu/recordings (kept 30 days)"
fi

chown -R vstream:vstream "$APP"

say "Services (start automatically, restart if they stop)..."
cat > /etc/systemd/system/mediamtx.service <<EOF
[Unit]
Description=VStream media server (MediaMTX)
After=network-online.target
Wants=network-online.target

[Service]
User=vstream
WorkingDirectory=$APP/mediamtx
ExecStart=$APP/mediamtx/mediamtx $YML
Restart=always
RestartSec=3

[Install]
WantedBy=multi-user.target
EOF
cat > /etc/systemd/system/vstream.service <<EOF
[Unit]
Description=VStream server
After=network-online.target mediamtx.service
Wants=network-online.target

[Service]
User=vstream
WorkingDirectory=$APP
EnvironmentFile=-$APP/vstream.env
ExecStart=/usr/bin/java -Djava.awt.headless=true -Dvstream.mediaHost=$DOMAIN -cp out com.vstream.Vidiserver.VideoServerMain
Restart=always
RestartSec=3

[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload
systemctl enable mediamtx vstream >/dev/null 2>&1
systemctl restart mediamtx
systemctl restart vstream

say "HTTPS website (Caddy, free certificate from Let's Encrypt)..."
if ss -ltnp 2>/dev/null | grep -E ':(80|443) ' | grep -vq caddy; then
  echo "  WARNING: another web server is using port 80/443:"
  ss -ltnp | grep -E ':(80|443) ' | grep -v caddy | sed 's/^/    /'
  echo "  Stop it (for example: systemctl disable --now apache2 nginx) and run this script again."
fi
# Other sites on this server (the Vistr game, future games...) live in the same Caddyfile between
# "# >>> name" and "# <<< name" lines. Keep them: only the memoryq.com part is rewritten.
KEEP=""
if [ -f /etc/caddy/Caddyfile ]; then
  cp /etc/caddy/Caddyfile /etc/caddy/Caddyfile.before-memoryq
  KEEP="$(awk '/^# >>> /{on=1} on{print} /^# <<< /{on=0}' /etc/caddy/Caddyfile)"
fi
# Vistr installed here but its site missing from Caddy (removed by an older deploy): put it back.
if [ -f /etc/vistr.env ] && ! printf '%s\n' "$KEEP" | grep -q '^# >>> vistr'; then
  VPORT="$(grep -E '^PORT=' /etc/vistr.env | cut -d= -f2)"
  KEEP="$KEEP
# >>> vistr (vistr.memoryq.com) - added by setup-server.sh
vistr.memoryq.com {
	reverse_proxy 127.0.0.1:${VPORT:-3000} {
		flush_interval -1
	}
}
# <<< vistr"
  echo "  Vistr's site (vistr.memoryq.com) was missing from Caddy - put back (game port ${VPORT:-3000})"
fi
cat > /etc/caddy/Caddyfile <<EOF
$DOMAIN, www.$DOMAIN {
	@www host www.$DOMAIN
	redir @www https://$DOMAIN{uri} permanent
	handle_path /hls/* {
		reverse_proxy 127.0.0.1:8888
	}
	handle_path /webrtc/* {
		reverse_proxy 127.0.0.1:8889
	}
	handle {
		reverse_proxy 127.0.0.1:8080 {
			flush_interval -1
		}
	}
}

# Mkarimu TV (the video site): the same server; its home page is the TV wall
${VIDEO_DOMAIN:-mkarimu.net}, www.${VIDEO_DOMAIN:-mkarimu.net} {
	@www host www.${VIDEO_DOMAIN:-mkarimu.net}
	redir @www https://${VIDEO_DOMAIN:-mkarimu.net}{uri} permanent
	handle_path /hls/* {
		reverse_proxy 127.0.0.1:8888
	}
	handle_path /webrtc/* {
		reverse_proxy 127.0.0.1:8889
	}
	handle {
		reverse_proxy 127.0.0.1:8080 {
			flush_interval -1
		}
	}
}
EOF
if [ -n "$KEEP" ]; then
  printf '\n%s\n' "$KEEP" >> /etc/caddy/Caddyfile
  echo "  kept other sites on this server: $(printf '%s\n' "$KEEP" | grep -c '^# >>> ')"
fi
if command -v caddy >/dev/null && ! caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile >/dev/null 2>&1; then
  echo "  WARNING: the new Caddy settings did not pass the check - putting the previous ones back"
  [ -f /etc/caddy/Caddyfile.before-memoryq ] && cp /etc/caddy/Caddyfile.before-memoryq /etc/caddy/Caddyfile
fi
systemctl enable caddy >/dev/null 2>&1
systemctl restart caddy

say "Firewall..."
SSH_PORT="$(echo "${SSH_CONNECTION:-x x x 22}" | awk '{print $4}')"
ufw allow "$SSH_PORT/tcp" >/dev/null
ufw allow OpenSSH >/dev/null
for p in 80/tcp 443/tcp 8189/udp 1935/tcp 8010:8099/tcp; do ufw allow "$p" >/dev/null; done
echo "  radio live DJ ports 8010-8099 open (one per station)"
# 8080 (server), 8888 (live video) and 8889 (calls) are reached through HTTPS now; only old app versions use them directly.
if [ "${KEEP_OLD_PORTS:-0}" = 1 ]; then
  for p in 8080/tcp 8888/tcp 8889/tcp; do ufw allow "$p" >/dev/null; done
  echo "  old ports 8080, 8888, 8889 left open (for phones with an old app)"
else
  for p in 8080/tcp 8888/tcp 8889/tcp; do ufw delete allow "$p" >/dev/null 2>&1 || true; done
  echo "  old ports 8080, 8888, 8889 closed - everything goes through HTTPS (443)"
fi
ufw --force enable >/dev/null
ufw status | sed 's/^/  /'

say "Checking..."
sleep 6
for s in mediamtx vstream caddy; do echo "  $s: $(systemctl is-active $s)"; done
if curl -fs -m 5 http://127.0.0.1:8080/channels >/dev/null; then echo "  VStream answers on port 8080"; else echo "  WARNING: VStream does not answer yet - see: journalctl -u vstream -n 50"; fi
journalctl -u vstream -n 40 --no-pager | grep -E 'Radio:|News:|Profiler:' | sed 's/^.*\]: /  /' || true
DNS_IP="$(getent ahostsv4 "$DOMAIN" 2>/dev/null | awk 'NR==1{print $1}' || true)"
if [ "$DNS_IP" = "$PUBLIC_IP" ]; then
  echo "  $DOMAIN points to this server ($PUBLIC_IP) - the HTTPS certificate is issued automatically."
else
  echo "  WARNING: $DOMAIN points to ${DNS_IP:-nothing}, not to this server ($PUBLIC_IP)."
  echo "  In Cloudflare, set the record for $DOMAIN (and www) to DNS only (grey cloud) with IP $PUBLIC_IP."
  echo "  HTTPS starts working by itself a few minutes after that."
fi
if [ "$FIRST" = 1 ] && ! [ -s "$APP/users.txt" ]; then
  journalctl -u vstream -n 80 --no-pager | grep -A2 'FIRST START' | sed 's/^.*\]: /  /' || true
fi
rm -rf "$TMP"
echo
echo "DONE."
echo "  Homepage:                   https://$DOMAIN"
echo "  Web dashboard:              https://$DOMAIN/me"
echo "  Admin page:                 https://$DOMAIN/admin"
echo "  Terms / Privacy:            https://$DOMAIN/terms   https://$DOMAIN/privacy"
echo "  Phone app server address:   $DOMAIN"
'@
  [System.IO.File]::WriteAllText($sh, ($script -replace "`r`n", "`n") + "`n", $utf8)
  ""
  "Uploading to the VPS as $vpsUser@$vps. Type the VPS password when asked (the letters do not show)..."
  & $scpExe -o StrictHostKeyChecking=accept-new $pkg $sh "${vpsUser}@${vps}:"
  if ($LASTEXITCODE -ne 0) { "UPLOAD FAILED - check the username and password and that the VPS is on, then run this script again."; return }
  ""
  "Installing on the VPS (a few minutes the first time). Type the VPS password again;"
  "if it then asks [sudo] password, type the same password once more."
  $keep = if ($KeepOldPorts) { "1" } else { "0" }
  $remote = 'sudo SSH_CONNECTION="$SSH_CONNECTION" KEEP_OLD_PORTS=' + $keep + ' PKG="$HOME/vstream-deploy.zip" bash "$HOME/vstream-install.sh" ' + $domain
  & $sshExe -t -o StrictHostKeyChecking=accept-new "$vpsUser@$vps" $remote
  if ($LASTEXITCODE -ne 0) { "THE VPS INSTALL STOPPED - send me the last lines above."; return }

  $scripts = "$root\scripts"
  New-Item -ItemType Directory -Force $scripts | Out-Null
  if ($__self -and (Test-Path $__self) -and -not $__self.StartsWith($scripts)) {
    Move-Item $__self (Join-Path $scripts "deploy-vps.ps1") -Force
    "This script was moved to $scripts (run it from there to send future updates to the VPS)."
  }
  ""
  "ALL DONE."
  "  Homepage: https://$domain    Web app: https://$domain/me    Admin: https://$domain/admin"
  "  Terms: https://$domain/terms    Privacy: https://$domain/privacy"
}
