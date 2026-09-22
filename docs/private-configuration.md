# Private NikTV configuration

NikTV no longer inserts profile credentials or service tokens into an APK. On the first device, open **Settings → Profiles → Private configuration**. The owner defaults to `nikhilmenghani`, the repository to `myenv`, and the configuration file to `.env`; all three remain editable. Enter a GitHub token with **Contents: read** access to that repository, or leave the token field blank to reuse a token already entered in GitHub backup settings. Tap **Save and sync**. The button remains available so missing or invalid fields can show an explanation. After a successful sync, turn on the preconfigured profiles you want to use. The token and only NikTV's recognized keys are encrypted locally with an Android Keystore key. The cached values load immediately at startup; WorkManager and app startup refresh them at most once every 24 hours. **Save and sync** forces an immediate refresh.

To configure another device, connect both to the same local network. On the new device, open **Settings → Profiles → Pair devices** and select **Receive configuration**. It shows a QR code, local address, port and one-time code for three minutes. Scan the QR code with the configured device and open the NikTV link; the pairing fields are filled in, but **Approve new device** must still be selected. If QR scanning is unavailable, enter the address, port and code manually on the configured device. The transfer is encrypted with the random one-time code; the receiving device stores the token with its own Android Keystore key and then syncs GitHub. A paired device cannot approve further devices. If you later enter a GitHub token manually on it, it becomes eligible to approve pairing. Pairing does not transfer watch history, backups, or device settings. Close the pairing screen or select **Cancel pairing** to stop listening.

For a device on another network, deploy the [pairing relay](../pairing-relay/README.md) behind HTTPS. Enter its URL on the new device and choose **Request remote pairing**. Send the link or QR code to the device where the token was manually entered, open it in NikTV, and select **Approve remote device**. The relay keeps only an encrypted package for up to 15 minutes and removes it after pickup. Keep the receiving screen open until pairing completes.

Without a relay, select **Create encrypted pairing file** on the token-owning device. Send the saved JSON file to the other device, and communicate the separately displayed 22-character unlock code through another channel. The receiving device selects the file, enters the code, and chooses **Import configuration**. The file expires in 24 hours. Delete the file and the message containing the unlock code after import. Both remote and file pairing store credentials on the new device as a paired (non-approving) device.

The file may be JSON with top-level string values, or `.properties`/`.env` lines in `KEY=value` form. Supported keys are:

```text
NIKTV_DEFAULT_PROFILE_NAME
NIKTV_DEFAULT_PORTAL_URL
NIKTV_DEFAULT_MAC_ADDRESS
NIKTV_DEFAULT_SERIAL_NUMBER
NIKTV_XTREAM_PROFILE_NAME
NIKTV_XTREAM_PORTAL_URL
NIKTV_XTREAM_USERNAME
NIKTV_XTREAM_PASSWORD
NIKTV_TMDB_API_KEY
NIKTV_TMDB_READ_ACCESS_TOKEN
OPEN_SUBTITLES_KEY
G_TOKEN
```

The IPTV profile values appear in Settings → Profiles after a successful sync. `G_TOKEN` is optional; the GitHub backup screen can also accept a token entered directly on the device. The app keeps the last successful cache when GitHub is unavailable. A `G_TOKEN` GitHub Actions secret is available only during a workflow run; it cannot be used by an installed app without copying it into the APK. Keep the token out of APK assets. Rotate any credentials previously embedded in published APKs.
