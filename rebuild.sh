#!/bin/bash
# Script to completely rebuild the project from scratch

echo "Cleaning all build files..."
./gradlew clean

echo "Removing build directories manually..."
find . -name "build" -type d -exec rm -rf {} +

echo "Building the library module first..."
./gradlew :linklab:assemble

echo "Building the sample app..."
./gradlew :sample:assembleDebug

echo "Build completed. The sample app should now use the latest library code."
echo "If you encounter any issues, try running:"
echo "  rm -rf ~/.gradle/caches/"
echo "to clear the Gradle cache completely."