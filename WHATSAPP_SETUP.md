# WhatsApp parent automation (Baileys)

```
Browser (sms1 Settings → WhatsApp)  →  Spring Boot (sms2)  →  whatsapp-service (Node + Baileys)  →  WhatsApp
```
Each school links **its own number** (one Baileys session per `schoolId`). Parents see messages from that school's number.

## A. One-time server setup (you, the software owner)

1. Run the gateway on the same VPS as the backend (Node 20+):
   ```bash
   cd whatsapp-service
   cp .env.example .env        # set API_KEY (openssl rand -base64 32)
   npm install
   npm install -g pm2 && pm2 start server.js --name wa-gateway && pm2 save && pm2 startup
   ```
   (or `docker build -t wa-gateway . && docker run -d -p 127.0.0.1:3001:3001 -v wa_sessions:/app/sessions --env-file .env wa-gateway`)
2. In the backend `.env` set `WHATSAPP_API_KEY=<same key>` (and `WHATSAPP_SERVICE_URL` if not on the same machine), restart Spring Boot. Tables `whatsapp_config` / `whatsapp_log` are created automatically.
3. Keep port 3001 **private** (localhost / firewall). Only Spring Boot should reach it.
4. **Back up `whatsapp-service/sessions/`** — it holds every school's login. Lose it and every school must re-scan.
5. Deploy the updated frontend (`settings.html`, `settings.js`, `shared-data.js`, `whatsapp-settings.js`).

## B. What a school must do (5 minutes)

1. Get a **dedicated number** (a spare SIM) with WhatsApp or WhatsApp Business installed on a phone. Don't use the principal's personal number.
2. Log in → **Settings → WhatsApp** → **Show QR code**.
3. On that phone: WhatsApp → **Linked devices → Link a device** → scan. (No camera access? Enter the number, click *Get link code*, then on the phone choose *Link with phone number instead* and type the code.)
4. Status turns **Connected**. Tick **Turn automation ON**, choose *absent* and/or *fee receipt*, edit the message text, **Save**, then **Send test** to your own number.
5. Make sure each student has the parent's **WhatsApp Number** filled in (Students → WhatsApp Number, e.g. `03XX-XXXXXXX` or `+92 3XX XXXXXXX`). Messages go to this number only, never to the primary phone. Empty, wrong or placeholder numbers (like `11111`, `0300-0000000`, `0300-1234567`) are skipped automatically.
6. Keep that phone charged and online (opens WhatsApp at least once every ~2 weeks, or WhatsApp unlinks it).

After that: marking a student **absent** (by teacher, admin or biometric save) messages the parent once per day; recording a fee payment sends a receipt; *Send a message to parents now* broadcasts announcements/fee reminders to a class or everyone.

## C. Rules that keep the number safe
- Baileys is **unofficial** — WhatsApp can ban numbers that look like spam. Use a dedicated number, let it live a few days on WhatsApp first, ask parents to save the number, and only send useful messages.
- The gateway already spaces messages 4–9 s apart, caps 400 per day per school, and skips numbers not on WhatsApp.
- If you need guaranteed delivery at scale, the official WhatsApp Business Cloud API is the only fully safe route.
