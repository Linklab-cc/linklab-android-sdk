# Publishing the LinkLab Android SDK

This document explains how to publish the LinkLab Android SDK to Maven Central via Sonatype OSSRH.

## Prerequisites

1. A Sonatype OSSRH account (https://central.sonatype.org/publish/publish-guide/)
2. GPG key for signing artifacts

## Configuration

The repository is already configured with Gradle Nexus Publish Plugin for publishing to Maven Central. You need to provide the following credentials:

### In your `~/.gradle/gradle.properties` file:

```properties
# Sonatype OSSRH Credentials
ossrhUsername=your_sonatype_username
ossrhPassword=your_sonatype_password

# Signing Configuration (choose one approach)
# Option 1: Keyring file
signing.keyId=your_gpg_key_id_last_8_chars
signing.password=your_gpg_key_password
signing.secretKeyRingFile=/path/to/your/gpg/secring.gpg

# Option 2: In-memory signing with GPG
signing.gnupg.keyName=your_gpg_key_id
signing.gnupg.passphrase=your_gpg_key_password
```

Alternatively, you can provide these as environment variables:
- `OSSRH_USERNAME`
- `OSSRH_PASSWORD`

## Release Process

### 1. Update Version

Update the version in `gradle.properties`:

```properties
version=0.0.1-SNAPSHOT  # for a snapshot release
# or
version=0.0.1  # for a release version
```

### 2. Build the Project

```bash
./gradlew clean build
```

### 3. Publish to Sonatype OSSRH

#### For a Snapshot Release

```bash
./gradlew publishToMavenCentral
```

This will publish to the Sonatype snapshots repository.

#### For a Release Version

```bash
./gradlew publishAndRelease
```

Or run the steps separately:

```bash
# Step 1: Publish to Sonatype
./gradlew :linklab:publishLinkLabToSonatype

# Step 2: Close and release the Sonatype staging repository
./gradlew :linklab:releaseSonatypeRepository
```

This will:
1. Upload artifacts to Sonatype OSSRH
2. Close the staging repository
3. Release the staging repository

## Common Issues and Solutions

### 1. Signing Failed

If signing fails, verify your GPG configuration:
- Check that your key is valid and not expired
- Ensure the key ID and password are correct
- Confirm the secret keyring file path is correct

### 2. Sonatype Repository Issues

If the Sonatype repository operation fails:
- Verify your credentials
- Check if you have the proper permissions
- Ensure your artifacts meet Maven Central requirements

### 3. Artifact Validation

Before publishing, verify your artifacts contain the proper information:
- Check the POM file contains all required information
- Verify Javadoc and source jars are included
- Ensure all artifacts are properly signed