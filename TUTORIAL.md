# Tutorial — Installare SC Viewer su Fire TV Stick con Downloader

Guida completa per chi non ha mai fatto sideload: senza computer, solo con
il telecomando e l'app **Downloader**.

> **Tempo richiesto:** ~10 minuti · **Codice breve:** `7770492`

---

## 1. Abilita le origini sconosciute

1. Dalla home, vai su **Impostazioni** (icona rotella in alto).
2. **Il mio Fire TV** → **Informazioni su**.
3. Evidenzia la prima voce (il **nome del dispositivo**) e premi **OK 7 volte
   di fila** — appare "Non serve fare altro, sei ora uno sviluppatore".
4. Torna indietro su **Il mio Fire TV** → **Opzioni sviluppatore** →
   **App di origine sconosciute** → **ON** → "Consenti" nell'avviso.

> Su alcuni Fire OS recenti l'interruttore è per-app: se non lo trovi,
> finisci prima il passo 2 e poi attiva
> **Impostazioni → Applicazioni → Origini applicazioni sconosciute →
> Downloader → ON**.

## 2. Installa Downloader

1. Torna alla home, apri **Cerca** (icona lente) e digita `downloader`.
2. Scarica **Downloader** di **AFTVnews** (icona arancione con freccia
   rossa) → **Ottieni / Scarica** → **Apri**.
3. Al primo avvio **consenti l'accesso a foto, media e file**
   (serve per salvare l'APK). Chiudi la schermata di benvenuto.

## 3. Scarica e installa SC Viewer (codice 7770492)

1. Nella home di Downloader seleziona la barra
   **"Enter a URL or Search Term"**.
2. Digita solo il codice **`7770492`** (tutti numeri, comodo col telecomando)
   e premi **Vai**. Downloader traduce i codici numerici in
   `aftv.news/7770492` — in alternativa puoi digitare l'indirizzo completo.
3. La pagina "Redirect… AFTVnews" ti porta automaticamente sulla
   **pagina GitHub della release**.
4. Scorri fino alla sezione **Assets** (premi OK per espanderla se è
   chiusa) e clicca su **`app-release.apk`**.
5. Il download parte (~1,3 MB). A fine download Downloader chiede di
   installare → **Installa** → **Apri** (o **Fatto**).
6. (Opzionale) Fatto questo puoi cancellare l'APK dai download di
   Downloader per liberare spazio.

> **Link diretto (piano B):** se il codice non funzionasse, digita nella
> barra di Downloader:
> `github.com/hackyday/SCViewer/releases/download/v1.0.0/app-release.apk`

## 4. Avvia SC Viewer

1. Torna alla home Fire TV → riga **Le tue app** → scorri fino in fondo →
   **Vedi tutto**.
2. Apri **SC Viewer** (banner rosso 16:9 "SC VIEWER").
3. Consiglio: evidenziato il banner premi il **tasto menu (☰)** del
   telecomando → **Sposta in alto** per tenerlo in prima riga.
4. All'avvio l'app carica subito il catalogo dal dominio in cache e
   sincronizza il dominio aggiornato in background.

## Comandi del telecomando

| Tasto | Azione |
|---|---|
| Frecce D-pad | Muovi il **cursore virtuale** (accelera tenendo premuto) |
| OK | **Clic** dove punta il cursore · tenuto premuto → **Preferiti** |
| Menu (☰) | Barra inferiore: Home / Preferiti / Cronologia / Ricarica / Indietro / Impostazioni |
| **Play/Pausa** | **Pausa / riprendi il film** in riproduzione |
| Indietro | Pagina precedente · chiude menu/overlay |
| Su/Giù contro il bordo dello schermo | Scorre la pagina |
| Durante un film | Il cursore **sparisce da solo**; qualunque freccia lo fa tornare |

## Problemi comuni

| Problema | Soluzione |
|---|---|
| "Installazione bloccata" | Attiva le origini sconosciute **per Downloader** (Impostazioni → Applicazioni → Origini applicazioni sconosciute) |
| Il codice non scarica l'APK | Usa il link diretto del piano B al passo 3 |
| Pagina nera / non carica | Menu (☰) → **Ricarica**; l'app risincronizza il dominio da sola (anche ogni 30 min in background) |
| Non riesco a cliccare nulla | Menu (☰) → Ricarica; se persiste chiudi e riapri l'app — si auto-ripristina |
| Spazio insufficiente | Disinstalla qualche app inutilizzata; l'APK è solo 1,3 MB |

## Aggiornare l'app in futuro

Quando esce una nuova release, ripeti il passo 3 con lo stesso codice
(aggiornato alla nuova versione) e installa sopra: **i preferiti, la
cronologia e le impostazioni vengono conservati**.
