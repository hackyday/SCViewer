# VPN — istruzioni (PC e telefono)

I client sono già installati su questo PC: **wireguard-tools** (`wg-quick`) e **openvpn**.
Manca solo una cosa: un account VPN con cui generare un profilo (creare l'account può
farlo solo l'utente, richiede una email di verifica).

## PC (Linux)

Opzione consigliata — **ProtonVPN Free** (gratis, nessun limite di banda):

1. Crea l'account su https://account.protonvpn.com/signup (plan Free).
2. Da https://account.protonvpn.com/downloads → **WireGuard configuration**:
   scegli Linux, generi il file `.conf`.
3. Salva il file in `/etc/wireguard/`, es.:
   ```bash
   sudo cp ~/Downloads/protonvpn.conf /etc/wireguard/protonvpn.conf
   sudo chmod 600 /etc/wireguard/protonvpn.conf
   ```
4. Attiva / disattiva:
   ```bash
   sudo wg-quick up protonvpn      # ON
   sudo wg-quick down protonvpn    # OFF
   curl -s https://ifconfig.me      # verifica: deve mostrare l'IP della VPN
   ```

Con `wg-quick up` attivo TUTTO il traffico del PC esce dall'IP della VPN, non dal tuo.

## Telefono (dove gira SCViewer)

1. Installa **ProtonVPN** dal Play Store (plan Free) → login → Connect.
2. Nel profilo VPN di Android attiva **"VPN Always-on"** (Impostazioni → Rete → VPN →
   ProtonVPN → Always-on + "Block connections without VPN"): così anche se il tunnel
   cade, il telefono non sfila traffico in chiaro dal tuo IP.
3. La VPN di sistema copre TUTTE le app, SCViewer incluso: il sito e il CDN vedono
   l'IP del server VPN, mai il tuo.

## Fire TV Stick

Lo stick non ha client VPN nativo. Alternative pratiche:
- router con VPN client (WireGuard/WiFi protetta a livello di rete — copre tutto il
  traffico domestico), oppure
- app tipo "VPN hotspot" per condividere dal telefono, oppure
  usare lo stick normalmente e accettare che il traffico dello stick parta dall'IP
  di casa (il sito vede comunque solo l'UA desktop falsificato).
