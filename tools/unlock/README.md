# Phone fingerprint unlock: install, test, undo

**Po slovensky (skratka):** Tento návod nainštaluje odomykanie odtlačkom z telefónu pre `sudo`, polkit (1Password,
systémové dialógy) a zamknutú obrazovku. Zmena PAM je jediný krok, ktorým sa dá zamknúť z vlastného PC, preto: vždy
nechaj otvorený root shell (`sudo -i` v druhom termináli), zálohuj `/etc/pam.d/sudo` a `/etc/pam.d/polkit-1`, testuj
v *novom* termináli a keď niečo nejde, vráť zálohy z root shellu (krok 8). Heslo funguje vždy ako záloha. Samoaktualizáciu
PhoneBoxu vypni, inak ti prepíše tento build. Kábel na USB musí byť dátový (terajší len nabíja).

Commands are for this machine (Fedora, GNOME, user in `$USER`). Run everything from the repository root of this branch.

> ## Safety first
> Changing PAM is the one step that can lock you out of `sudo`.
> 1. **Keep a root shell open** in a second terminal (`sudo -i`) from Step 4 until Step 7 has passed. Do not close it.
> 2. **Back up** each PAM file before touching it (Steps 5 and 7).
> 3. **Test in a new terminal**, never in the root shell.
> 4. **Undo** from the root shell (Step 8):
>    `cp /etc/pam.d/sudo.bak /etc/pam.d/sudo; cp /etc/pam.d/polkit-1.bak /etc/pam.d/polkit-1`
>
> The helper fails closed: any error, no key, no phone, no terminal or a locked session just falls through to the
> normal password prompt.

## 1. Build and install the agent and the phone app from the branch

Turn **off** self-update first, or PhoneBox would replace this build with upstream: agent settings
(<http://127.0.0.1:8765>) → **Updates** → switch **Install new releases automatically** off.

Agent (builds with the .NET 10 SDK, stops the running agent, copies the files to `~/.local/share/PhoneBox/app`,
starts it again; settings in `~/.config/PhoneBox` are kept):

```sh
./scripts/install.sh --autostart      # --autostart = systemd user service `phonebox`; drop it if you do not use it
```

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
2. On the phone, the panel shows **Set up fingerprint unlock**. Tap it, confirm with your fingerprint, and note the
   20-character check code it shows (format `AB12 CD34 EF56 7890 1A2B`).

## 3. Install the helper and enroll the phone key

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
file are owned by root and not writable by group or others, so do not change that.

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
| `sudo -k; sudo true`, press **Enter** | Password prompt at once; the correct password works. |
| Phone Wi-Fi off, then `sudo -k; sudo true` | Password prompt with no delay. |
| `sudo -k; echo \| sudo -S true` (no terminal) | Password path, no phone card. |
| `ssh localhost sudo -k true` (if sshd runs) | No phone card. |

Without an answer the phone request times out after 20 s and falls back to the password.

## 7. polkit and 1Password

Root shell still open.

```sh
sudo cp /etc/pam.d/polkit-1 /etc/pam.d/polkit-1.bak
sudo nano /etc/pam.d/polkit-1
```

Add the same line **above** `auth include system-auth`:

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

### Screen unlock (GNOME lock screen)

Lock with **Super+L**. Within about 2 s the phone shows **Unlock PC**; tap it and use the fingerprint: the screen
unlocks. Cancel the fingerprint instead: the screen stays locked and your password still works.

## 8. Undo

From the root shell (works even if `sudo` itself is broken):

```sh
cp /etc/pam.d/sudo.bak /etc/pam.d/sudo
cp /etc/pam.d/polkit-1.bak /etc/pam.d/polkit-1
```

To turn the feature off without touching PAM, untick the checkbox in the agent settings. To remove it completely
afterwards: `sudo rm -r /usr/local/lib/phonebox /etc/phonebox`. (With the PAM lines still present but the helper
gone, `pam_exec` fails and PAM goes on to the password.)

## Known limits

- The first 1Password unlock after a reboot needs the master password; system authentication works afterwards.
- GDM login after boot is not covered (only the lock screen when already logged in).
- On polkit the phone card can only say "System authentication"; polkit passes no command.
- Only the user who ran `enroll` is covered; a request must come from a local terminal (sudo) or the local session
  (polkit), never over SSH.
