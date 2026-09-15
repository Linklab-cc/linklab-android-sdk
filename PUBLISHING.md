# Publishing the Linklab Android SDK

Artifacts are published to Maven Central as `cc.linklab:android` through the
[Sonatype Central Portal](https://central.sonatype.com/).

## Prerequisites

1. A Central Portal account with publishing rights for the `cc.linklab` namespace and a
   generated **user token** (Account -> Generate User Token). The token username/password are
   what `ossrhUsername` / `ossrhPassword` refer to below.
2. A GPG key whose **public key is on a public keyserver**. Upload it once:

   ```bash
   gpg --keyserver keys.openpgp.org --send-keys YOUR_KEY_ID
   gpg --keyserver keyserver.ubuntu.com --send-keys YOUR_KEY_ID
   ```

   Wait a few minutes for propagation before the first publish. Export the secret keyring if you
   do not have one yet: `gpg --export-secret-keys YOUR_KEY_ID > ~/.gnupg/secring.gpg`.

## Credentials are never stored in the repository

`gradle.properties` in this repo deliberately keeps `ossrhUsername`, `ossrhPassword` and the
`signing.*` properties **empty**. Supply them at publish time either

- on the command line with `-P` flags (shown below), or
- in your private `~/.gradle/gradle.properties`, or
- via the environment variables `OSSRH_USERNAME` and `OSSRH_PASSWORD` (credentials only).

Signing is skipped automatically when `signing.keyId` is empty, so everyday builds and CI do not
need a key.

## Release steps

1. Set the version in `gradle.properties` (`version=0.1.0`) and update `CHANGELOG.md`.
2. Make sure the build is green:

   ```bash
   ./gradlew :linklab:assembleRelease :linklab:testReleaseUnitTest --no-daemon
   ```

3. Build, sign, bundle and upload to the Central Portal:

   ```bash
   ./gradlew publishToCentralPortal \
     -PossrhUsername=<token-username> \
     -PossrhPassword=<token-password> \
     -Psigning.keyId=<last 8 hex chars of the key id> \
     -Psigning.password=<key passphrase> \
     -Psigning.secretKeyRingFile=/absolute/path/to/secring.gpg
   ```

   `publishToCentralPortal` is defined in `linklab/build.gradle.kts`; it publishes the release
   publication (AAR, sources, javadoc, POM, signatures) to `linklab/build/maven-bundle`, zips it
   to `linklab/build/deployment-bundle.zip` and uploads the zip with the Central Portal publisher
   API. `./gradlew publishRelease` (root project) does the same after a `clean` and refuses
   `-SNAPSHOT` versions.

4. Check https://central.sonatype.com/publishing. The deployment validates within a couple of
   minutes and appears on Maven Central 15-30 minutes after it is published.
5. Tag the release: `git tag v0.1.0 && git push origin v0.1.0`.

## Manual upload (fallback)

```bash
./gradlew :linklab:createDeploymentBundle -Psigning.keyId=... -Psigning.password=... -Psigning.secretKeyRingFile=...
```

Then upload `linklab/build/deployment-bundle.zip` by hand at
https://central.sonatype.com/publishing -> Publish Component.

## Legacy OSSRH

The root build still applies the Nexus publish plugin and defines `publishToMavenCentral`
(snapshots) and `publishAndRelease` (releases) for accounts that remain on the legacy
`s01.oss.sonatype.org` staging flow. They read the same `ossrhUsername` / `ossrhPassword`
properties. New publishers should use the Central Portal flow above.

## Troubleshooting

- **"Could not find a public key by the key fingerprint"** - the GPG public key is not (yet) on a
  keyserver. Upload it (see Prerequisites) and retry after a few minutes.
- **Signing fails** - check the key id (last 8 hex characters), passphrase and that
  `signing.secretKeyRingFile` is an absolute path to an exported secret keyring.
- **`ossrhUsername not provided`** - the property is empty; pass it with `-P` or in
  `~/.gradle/gradle.properties`.
- **Validation errors in the portal** - the POM must include name, description, url, license,
  developers and scm; all of these come from `gradle.properties` (`project*` keys).
