#!/usr/bin/env bash
#
# Rigenera l'asset frontend SCtv: app/src/main/assets/sctv/
#   - sctv.css   : Tailwind COMPILATO (MAI il CDN runtime: offline = spogliata)
#   - fonts/*.woff2 : Bebas Neue 400 + Plus Jakarta Sans (variabile, 200-800)
#
# Uso: tools/build-sctv.sh
# Richiede node/npm (fallback font: system-ui se il download fallisce —
# in quel caso l'output resta leggibile ma diverso dal design).
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ASSETS="$ROOT/app/src/main/assets/sctv"
WORK="${TMPDIR:-/tmp}/sctv-tailwind-build"
UA="Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"
GF_URL="https://fonts.googleapis.com/css2?family=Bebas+Neue&family=Plus+Jakarta+Sans:wght@400;500;600;700;800&display=swap"

mkdir -p "$ASSETS/fonts" "$WORK"
cd "$WORK"

# --- 1. dipendenza Tailwind (cache nel workdir temporaneo) -------------------
if [ ! -x node_modules/.bin/tailwindcss ]; then
  echo "[sctv] install tailwindcss@3.4.17 in $WORK ..."
  npm install --no-fund --no-audit --silent tailwindcss@3.4.17
fi

# --- 2. config Tailwind: copia del blocco inline del file normativo ----------
cat > tailwind.config.js <<'EOF'
/** Config Tailwind dell'interfaccia SCtv: copia del blocco inline del file
 *  normativo docs/sctv/sctv-interface.html (font + palette sctv). */
module.exports = {
  darkMode: 'class',
  content: [process.env.SCTV_CONTENT],
  theme: {
    extend: {
      fontFamily: {
        sans: ['Plus Jakarta Sans', 'system-ui', 'sans-serif'],
        display: ['Bebas Neue', 'Impact', 'sans-serif'],
      },
      colors: {
        sctv: {
          accent: '#E50914',
          accentHover: '#FF1E27',
          dark: '#0E0E10',
          darker: '#070709',
          card: '#18181B',
          surface: '#222226',
        }
      }
    }
  }
};
EOF

# --- 3. font WOFF2 locali (solo subset latin: copre le lettere italiane) -----
download_fonts() {
  echo "[sctv] download WOFF2 da Google Fonts ..."
  local css="fonts-gf.css"
  curl -sf --max-time 20 -A "$UA" "$GF_URL" -o "$css"

  python3 - "$css" "$ASSETS/fonts" <<'PYEOF'
import re, sys, urllib.request, os
css_path, dst = sys.argv[1], sys.argv[2]
css = open(css_path).read()
# (subset, body) per ogni @font-face; teniamo SOLO latin (accenti italiani ok)
blocks = re.findall(r'/\*\s*latin\s*\*/\s*@font-face\s*\{(.*?)\}', css, re.S)
targets = {}
for body in blocks:
    fam = re.search(r"font-family:\s*'([^']+)'", body).group(1)
    weight = re.search(r"font-weight:\s*(\d+)", body).group(1)
    url = re.search(r"url\((\S+?)\)", body).group(1)
    targets[(fam, weight)] = url

wanted = {('Bebas Neue', '400'): 'bebas-neue-400.woff2'}
# Plus Jakarta Sans: Google serve il FILE VARIABILE (stesso URL per ogni
# peso) -> un solo woff2 con range 200-800.
pjs = [u for (f, w), u in targets.items() if f == 'Plus Jakarta Sans']
if pjs:
    wanted[('PJS', 'var')] = 'plus-jakarta-sans-var.woff2'
    # memorizza l'URL PJS per il controllo di variabilità a valle
    with open(os.path.join(dst, '.pjs_url'), 'w') as fh:
        fh.write(pjs[0])

for key, fname in wanted.items():
    url = targets.get(key) or (pjs[0] if pjs and key[0] == 'PJS' else None)
    if not url:
        print('[sctv] WARN: font non trovato nel CSS:', key)
        continue
    data = urllib.request.urlopen(url, timeout=30).read()
    open(os.path.join(dst, fname), 'wb').write(data)
    print('[sctv]', fname, len(data), 'bytes')
PYEOF

  rm -f "$css"
}

# Ri-scarica solo se mancano (idempotente; forza con tools/build-sctv.sh -f)
if [ ! -s "$ASSETS/fonts/bebas-neue-400.woff2" ] || \
   [ ! -s "$ASSETS/fonts/plus-jakarta-sans-var.woff2" ] || [ "${1:-}" = "-f" ]; then
  download_fonts || echo "[sctv] WARN download font FALLITO: fallback system-ui (debito da risolvere)"
fi

if [ ! -s "$ASSETS/fonts/bebas-neue-400.woff2" ]; then
  echo "[sctv] ATTENZIONE: font Bebas assente — sctv.css NON dichiarerà @font-face Bebas"
fi

# --- 4. input.css (@font-face + direttive Tailwind) --------------------------
{
  if [ -s "$ASSETS/fonts/bebas-neue-400.woff2" ]; then
    cat <<'EOF'
/* Font WOFF2 locali (assets/sctv/fonts/): l'interfaccia gira OFFLINE, il
   bundle Google Fonts runtime è vietato (piano §1.5). font-display:swap.
   Plus Jakarta Sans è servito da Google come font VARIABILE: un solo file
   con range pesi 200-800. */
@font-face {
  font-family: 'Bebas Neue';
  font-style: normal;
  font-weight: 400;
  font-display: swap;
  src: url('fonts/bebas-neue-400.woff2') format('woff2');
}
EOF
  fi
  if [ -s "$ASSETS/fonts/plus-jakarta-sans-var.woff2" ]; then
    cat <<'EOF'
@font-face {
  font-family: 'Plus Jakarta Sans';
  font-style: normal;
  font-weight: 200 800;
  font-display: swap;
  src: url('fonts/plus-jakarta-sans-var.woff2') format('woff2');
}
EOF
  fi
  cat <<'EOF'

@tailwind base;
@tailwind components;
@tailwind utilities;
EOF
} > input.css

# --- 5. compilazione ----------------------------------------------------------
echo "[sctv] compile tailwind -> $ASSETS/sctv.css"
SCTV_CONTENT="$ASSETS/index.html" npx tailwindcss -c tailwind.config.js \
  -i input.css -o "$ASSETS/sctv.css" --minify

echo "[sctv] OK: $(wc -c < "$ASSETS/sctv.css") bytes css, fonts: $(ls "$ASSETS/fonts" | tr '\n' ' ')"
