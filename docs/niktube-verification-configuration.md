# NikTube playback verification

Create the repository Actions secret `NIKTUBE_BOTGUARD_API_KEY`. Android validation
passes it to Gradle as an environment variable. Signed release and development
workflows also supply it and refuse to publish APKs if it is absent. NikTube sends it only to
YouTube's `/api/jnn/v1/GenerateIT` verification endpoint, which obtains an
integrity token used to mint playback proof tokens. It does not authenticate a
NikTV user or access their Google account.

For local builds, provide the same environment variable or a Gradle property
named `NIKTUBE_BOTGUARD_API_KEY` in your user-level Gradle properties. Do not
commit the value. Builds without it remain usable for IPTV, but NikTube playback
reports that verification is not configured.

This build-time configuration avoids a literal credential in tracked source.
The value is included in the APK and can be extracted; GitHub Secrets cannot
make a key delivered to an Android client confidential. A truly private key
requires a service-side design rather than APK injection.

The previous bundled value was published by BgUtils upstream. Removing it from
the current source does not remove commit history or automatically resolve an
existing GitHub secret-scanning alert. Review that alert as a published upstream
library credential; no personal credential rotation is implied by this finding.

## Read the current upstream value

In GitHub Actions, select **Read upstream NikTube verification key**, then choose
**Run workflow** on `main`. The run summary shows the published `GOOG_API_KEY`
and a source link pinned to the exact BgUtils commit it was read from.

Copy the value into the `NIKTUBE_BOTGUARD_API_KEY` repository secret and your
local Gradle properties, then rebuild. If GitHub masks a value matching an
existing secret, open the pinned public source link to copy it.

The workflow runs only on request, uses no repository credentials, and does
not execute upstream code or modify secrets. It reads the latest published
value rather than issuing a new key or checking whether Google accepts it.
