#!/bin/bash

# Script to publish LinkLab Android SDK to Maven Central (New Portal)
# This script creates a bundle for upload to https://central.sonatype.com/

set -e

echo "🔨 Building and publishing to local bundle..."
./gradlew clean :linklab:publishToLocalBundle

BUNDLE_DIR="linklab/build/maven-bundle"
ZIP_FILE="linklab-android-sdk-bundle.zip"

echo ""
echo "📦 Creating deployment bundle..."
cd "$BUNDLE_DIR"

# Create zip file of all artifacts
zip -r "../$ZIP_FILE" .

cd - > /dev/null

echo ""
echo "✅ Bundle created successfully!"
echo ""
echo "📍 Bundle location: linklab/build/$ZIP_FILE"
echo ""
echo "📤 Next steps:"
echo "   1. Go to https://central.sonatype.com/publishing"
echo "   2. Click 'Publish Component' or upload button"
echo "   3. Upload the file: linklab/build/$ZIP_FILE"
echo "   4. Wait for validation (usually 1-2 minutes)"
echo "   5. Review and publish"
echo ""
echo "⏱  It takes 15-30 minutes for the artifact to appear on Maven Central after publishing."
echo ""

