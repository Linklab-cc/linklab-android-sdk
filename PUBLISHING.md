# Publishing the LinkLab Android SDK

This document explains how to publish the LinkLab Android SDK to Maven Central.

## Prerequisites

1. A Sonatype account (either Legacy OSSRH or New Central Portal)
2. GPG key for signing artifacts
3. **GPG public key uploaded to keyservers** (see below)

## Sonatype Systems

Sonatype has two publishing systems:

### New Central Portal (Recommended)
- Website: https://central.sonatype.com/
- Uses publishing tokens (not username/password)
- Simpler bundle-based upload process
- **Use this if your account was created after March 2024**

### Legacy OSSRH
- Website: https://s01.oss.sonatype.org/
- Uses JIRA credentials
- Uses staging repositories
- **Use this if you have an older Sonatype account**

## Configuration

### Step 1: Upload GPG Public Key (First Time Only)

**Important:** Maven Central requires your GPG public key to be available on public keyservers.

Run this once (already done if you just followed the setup):

```bash
./upload-gpg-key.sh
```

Or manually:
```bash
gpg --keyserver keys.openpgp.org --send-keys YOUR_KEY_ID
gpg --keyserver keyserver.ubuntu.com --send-keys YOUR_KEY_ID
```

**Wait 5-10 minutes** for key propagation before publishing.

### Step 2: Verify Credentials

The credentials are already in `gradle.properties`. Make sure they are correct:

```properties
# For New Portal: Get from https://central.sonatype.com/ -> Account -> Generate User Token
# For Legacy OSSRH: Use your Sonatype JIRA credentials
ossrhUsername=your_username_or_token
ossrhPassword=your_password_or_token

# Signing Configuration
signing.keyId=your_gpg_key_id_last_8_chars
signing.password=your_gpg_key_password
signing.secretKeyRingFile=../secring.gpg
```

## Publishing Process

### Step 1: Update Version

Update the version in `gradle.properties`:

```properties
version=0.0.1-SNAPSHOT  # for a snapshot release
# or
version=0.0.1  # for a release version (remove -SNAPSHOT)
```

### Step 2: Build the Project

```bash
./gradlew clean build
```

### Step 3: Publish

#### Option A: New Central Portal (Recommended - Automated)

Simply run:

```bash
./gradlew publishRelease
```

This will automatically:
1. ✅ Clean and build the project
2. ✅ Sign all artifacts with GPG
3. ✅ Create a deployment bundle
4. ✅ Upload to Central Portal API
5. ✅ Display upload status

After successful upload:
- Check status at: https://central.sonatype.com/publishing
- You may need to review and publish in the portal (usually auto-publishes)
- ⏱ **Sync time:** 15-30 minutes to appear on Maven Central

#### Option B: Legacy OSSRH (If you have old account)

For snapshots:
```bash
./gradlew publishToMavenCentral
```

For releases:
```bash
./gradlew publishAndRelease
```

Or run the steps separately:

```bash
# Step 1: Publish to Sonatype
./gradlew :linklab:publishLinkLabToSonatype

# Step 2: Close and release the staging repository
./gradlew :linklab:releaseSonatypeRepository
```

## Common Issues and Solutions

### 1. Invalid Signature / Public Key Not Found

**Error:** "Could not find a public key by the key fingerprint"

**Solution:** Your GPG public key needs to be uploaded to keyservers:
```bash
./upload-gpg-key.sh
```

Wait 5-10 minutes for propagation, then try publishing again.

### 2. Signing Failed

If signing fails, verify your GPG configuration:
- Check that your key is valid and not expired
- Ensure the key ID and password are correct
- Confirm the secret keyring file path is correct

### 3. Sonatype Repository Issues

If the Sonatype repository operation fails:
- Verify your credentials
- Check if you have the proper permissions
- Ensure your artifacts meet Maven Central requirements

### 4. Artifact Validation

Before publishing, verify your artifacts contain the proper information:
- Check the POM file contains all required information
- Verify Javadoc and source jars are included
- Ensure all artifacts are properly signed