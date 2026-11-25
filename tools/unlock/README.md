# Phone fingerprint unlock: install, test, undo

**Po slovensky (skratka):** Tento návod nainštaluje odomykanie odtlačkom z telefónu pre `sudo`, polkit (1Password,
systémové dialógy) a zamknutú obrazovku. Zmena PAM je jediný krok, ktorým sa dá zamknúť z vlastného PC, preto: vždy
nechaj otvorený root shell (`sudo -i` v druhom termináli), zálohuj `/etc/pam.d/sudo`. Súbor `/etc/pam.d/polkit-1` na
tomto Fedore neexistuje (je len `/usr/lib/pam.d/polkit-1`): najprv ho skopíruj z `/usr/lib/pam.d/polkit-1` a riadok pridaj
do kópie; vrátenie = `rm /etc/pam.d/polkit-1`. Testuj v *novom* termináli a keď niečo nejde, vráť zmeny z root shellu
(krok 8). Heslo funguje vždy ako záloha. Po každom novom nastavení na telefóne spusti znova
`sudo /usr/local/lib/phonebox/phonebox-unlock enroll`. Samoaktualizáciu PhoneBoxu vypni, inak ti prepíše tento build.
Kábel na USB musí byť dátový (terajší len nabíja).
Zamknuté PC odomkneš priložením prsta na snímač telefónu, bez okna na telefóne (len pre obrazovku; sudo a polkit
ostávajú s oknom). So zaškrtnutým „Follow the computer lock“ Super+L uspí aj mobil a jedno stlačenie tlačidla
napájania s prstom odomkne mobil aj PC. Na telefóne raz zapni Nastavenia › Dostupnosť › PhoneBox: sleep with the PC (funguje aj cez Wi-Fi). Po aktualizácii treba raz znova nastaviť kľúč na telefóne a spustiť `enroll`.

Commands are for this machine (Fedora, GNOME, user in `$USER`). Run everything from the repository root of this branch.

> ## Safety first
> Changing PAM is the one step that can lock you out of `sudo`.
> 1. **Keep a root shell open** in a second terminal (`sudo -i`) from Step 4 until Step 7 has passed. Do not close it.
> 2. **Back up** `/etc/pam.d/sudo` before touching it (Step 5). There is no `/etc/pam.d/polkit-1` on this Fedora (only
>    the vendor file `/usr/lib/pam.d/polkit-1`); Step 7 copies the vendor file to `/etc/pam.d/polkit-1` and edits the
>    copy, so the vendor file itself is the backup.
> 3. **Test in a new terminal**, never in the root shell.
> 4. **Undo** from the root shell (Step 8):
>    `cp /etc/pam.d/sudo.bak /etc/pam.d/sudo; rm /etc/pam.d/polkit-1` (polkit falls back to the vendor file)
>
> The helper fails closed: any error, no key, no phone, no terminal or a locked session just falls through to the
> normal password prompt.

## 1. Build and install the agent and the phone app from the branch

Turn **off** self-update first, or PhoneBox would replace this build with upstream: agent settings
(<http://127.0.0.1:8765>) → **Updates** → switch **Install new releases automatically** off.

Agent (builds with the .NET 10 SDK, stops the running agent, copies the files to `~/.local/share/PhoneBox/app`,
then starts it again, so you have a fresh agent before the first test; settings in `~/.config/PhoneBox` are kept):

```sh
./scripts/install.sh
```

Do not add `--autostart`: on this PC `~/.config/autostart/phonebox.desktop` already starts the agent at login, and the
flag would add a second systemd user service, so the agent would start twice.

Do not pass `--apk` here: that script may pick an older release APK from `dist/`. Build the debug APK instead:

```sh
cd android && JAVA_HOME=$HOME/.local/share/jdk-21 ./gradlew assembleDebug && cd ..
adb devices                           # the phone must be listed as "device"
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

The USB cable must carry **data** (the current one only charges, and `adb devices` will show nothing). Open the
PhoneBox panel on the phone afterwards.

## 2. Turn the feature on and start setup on the phone

1. Agent settings → tick **Fingerprint unlock from the phone (sudo, password dialogs such as 1Password, and the
   GNOME lock screen; Linux)**. The checkbox exists on Linux only.
2. On the phone, the panel shows **Set up fingerprint unlock**. Tap it and note the 20-character check code it shows
   (format `AB12 CD34 EF56 7890 1A2B`). Creating the key asks for no fingerprint; the phone has to have a fingerprint
   enrolled, and every later use of the key (each unlock) asks for it.

## 3. Install the helper and enroll the phone key

The agent must be running, the checkbox ticked and the phone set up (Step 2) first, otherwise `enroll` reports that
there is no phone key yet.

```sh
sudo dnf install python3-cryptography
sudo install -D -m 0755 tools/unlock/phonebox_unlock.py /usr/local/lib/phonebox/phonebox-unlock
sudo /usr/local/lib/phonebox/phonebox-unlock enroll
```

`enroll` asks the local agent for the phone's key and prints `Check code: ...`. If it equals the code on the phone,
answer `y`; anything else (or Enter) saves nothing. It writes `/etc/phonebox/<your user>.pub` (run it with `sudo` from
your own account, not from a root shell). Check:

```sh
ls -l /etc/phonebox/
```

Expected: `root root ... <user>.pub`. The helper ignores the key (password only) unless `/etc/phonebox` and the `.pub`
file are owned by root and not writable by group or others, so do not change that. Once this file exists, the agent
also checks screen unlocks against it (instead of the key the phone sent), so a key enrolled from another device
cannot unlock the screen.

**After every new setup on the phone** (a new phone, a reinstalled app, a new fingerprint, or tapping setup again) run
`sudo /usr/local/lib/phonebox/phonebox-unlock enroll` again and compare the new code; until then sudo, polkit and the
screen unlock fall back to the password. A setup that fails keeps the old key working. Updating to the build with the 5 s window key needs one new setup on the phone and `enroll` (the panel offers
**Set up fingerprint unlock** by itself).

## 4. Open a root shell and leave it open

In a **second terminal**: `sudo -i`. Leave it open until Step 7 has passed.

## 5. sudo: swap the Howdy line for the phone line

```sh
sudo cp /etc/pam.d/sudo /etc/pam.d/sudo.bak
sudo nano /etc/pam.d/sudo        # or any editor
```

Replace the line `auth sufficient pam_howdy.so no_confirmation` with exactly:

```
auth       sufficient   pam_exec.so quiet /usr/local/lib/phonebox/phonebox-unlock
```

Do not add pam_exec's `stdout` option. (`/etc/pam.d/gdm-password` is not touched; Howdy stays there.)

## 6. Test sudo (each in a NEW terminal)

| Command | Expected |
|---|---|
| `sudo -k; sudo true` | Prompt "Touch the sensor on the phone, or press Enter to type the password." The phone card shows `sudo true`. Fingerprint: succeeds with no password. |
| `sudo -k; sudo true`, press **Enter** at the prompt | Password prompt at once; the correct password works. |
| Phone Wi-Fi off, then `sudo -k; sudo true` | Password prompt with no delay. |
| `sudo -k; setsid -w sudo -S true </dev/null` (no terminal) | Password via the stdin path (fails here, as stdin is empty), no phone card. |
| `ssh -t localhost sudo -k true` (if sshd runs) | Password only, no phone card. |

Without an answer the phone request times out after 20 s and falls back to the password.

## 7. polkit and 1Password

Root shell still open.

On this Fedora, polkit's PAM file is the vendor file `/usr/lib/pam.d/polkit-1`; there is no `/etc/pam.d/polkit-1`.
A file in `/etc/pam.d` replaces the vendor one completely, so copy the vendor file first and edit the copy (never
create `/etc/pam.d/polkit-1` with only the new line: every polkit dialog would lose the password).

```sh
ls -l /etc/pam.d/polkit-1 /usr/lib/pam.d/polkit-1   # expect: only the /usr/lib one exists
sudo cp /usr/lib/pam.d/polkit-1 /etc/pam.d/polkit-1
sudo nano /etc/pam.d/polkit-1
```

In the new `/etc/pam.d/polkit-1`, add the same line **above** `auth include system-auth`:

```
auth       sufficient   pam_exec.so quiet /usr/local/lib/phonebox/phonebox-unlock
```

Test:

| Action | Expected |
|---|---|
| `pkexec true` | GNOME dialog; phone card "System authentication — ..."; fingerprint passes. |
| `pkexec true`, tap **Use password** on the phone | Password field at once; the password works. |
| `pkexec true`, do nothing | Password field after about 10 s. |
| Lock 1Password, unlock it with system authentication | Fingerprint; unlocked, and the Brave extension is unlocked too. |
| Phone Wi-Fi off, `pkexec true` | Password field at once. |
| `ssh -t localhost pkexec true` (if sshd runs) | Password only (text agent), no phone card. |

1Password and polkit support count as confirmed only once these tests pass on this PC.

### If no phone card appears for polkit

The helper fails closed and silently, so a blocked helper looks like "password only". Two known causes on Fedora:

- **SELinux** (Enforcing here): polkit's `polkit-agent-helper-1` runs as `policykit_auth_t`, which may be denied the TCP
  connection to `127.0.0.1:8765`, running `loginctl` or reading `/proc`. Look for denials right after a try:
  `sudo ausearch -m avc -ts recent | grep -i polkit`.
- **Socket-activated helper**: if a Fedora update enables polkit's helper unit (it runs with `PrivateNetwork=yes`, so
  it cannot reach the agent, and outside your session), check `systemctl is-enabled polkit-agent-helper.socket`.

Either way the password keeps working; sudo is not affected.
### Screen unlock (GNOME lock screen)

Lock with **Super+L**. Within about 2 s the phone shows "Touch the sensor to unlock the PC". Touch the fingerprint
sensor (on a side sensor, touch it, do not press it): the screen unlocks, with no dialog on the phone. A wrong finger
gives a short buzz and the phone keeps listening. The phone listens only while the PC is locked, the panel is in front
and the phone screen is on; a sudo or polkit card pauses it (those keep the fingerprint dialog, which shows what you
approve).

After too many wrong fingers Android stops the sensor for a while; the phone then shows the **Unlock PC** button again:
tap it and use the fingerprint in the dialog. Your password always works too.

**Sleep with the PC, one touch wakes both.** In the agent settings tick **Follow the computer lock** (needs ADB).
Super+L then also turns the phone screen off. Press the phone's power button and leave the finger on it: the phone
unlocks with its fingerprint and, within a second, the PC unlocks too. The unlock key opens for 5 s after any
fingerprint, so the phone's own unlock is enough. If you unlock the phone with its PIN instead, the panel shows the
touch hint: touch the sensor once more.

Some phones refuse the agent's sleep key over USB (Xiaomi without a SIM cannot turn on "USB debugging (Security
settings)"), and over Wi-Fi there is no ADB at all. The panel then puts the phone to sleep itself: turn on **Settings ›
Accessibility › PhoneBox: sleep with the PC** once (the panel's **Turn on** button opens that screen; on Android 13+
you may first need App info › ⋮ › **Allow restricted settings**). The service reads nothing on the screen; it only
locks it, like the power button. It works over Wi-Fi as well. Over USB, unlocking the PC by password lights the phone
again; over Wi-Fi, wake it with the button.

## 8. Undo

From the root shell (works even if `sudo` itself is broken):

```sh
cp /etc/pam.d/sudo.bak /etc/pam.d/sudo
rm /etc/pam.d/polkit-1      # polkit falls back to the vendor file /usr/lib/pam.d/polkit-1
```

To turn the feature off without touching PAM, untick the checkbox in the agent settings. To remove it completely
afterwards: `sudo rm -r /usr/local/lib/phonebox /etc/phonebox`. (With the PAM lines still present but the helper
gone, `pam_exec` fails and PAM goes on to the password.)

## Known limits

- The first 1Password unlock after a reboot needs the master password; system authentication works afterwards.
- GDM login after boot is not covered (only the lock screen when already logged in).
- On polkit the phone card can only say "System authentication"; polkit passes no command.
- Only the user who ran `enroll` is covered. The helper looks up the logind session of the process asking (walking up
  to 8 parents): an SSH or other remote session, or no session at all, means password only. sudo needs a local
  session (a text console is fine); polkit needs the local graphical session (Wayland or X11). Terminals and
  gnome-shell, which GNOME starts outside the session, count as local when your graphical session is local.
- A process started from SSH inside your user manager (`systemd-run --user`) looks like a desktop terminal and gets
  the phone card while your desktop session is local.
