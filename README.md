# LinkLab Android SDK

The LinkLab Android SDK makes it easy to implement dynamic links in your Android application. This SDK allows you to create, manage, and track deep links that work across platforms and provide a seamless user experience.

## Features

- Process dynamic links that launch your app
- Retrieve full links from short links
- Handle custom deep link routing
- Automatically track dynamic link analytics
- Capture Google Play install referrer information to track app installs from LinkLab links

## Requirements

- Android SDK 21+
- Kotlin 2.0+
- OkHttp 4.11.0+
- Google Play Install Referrer Library 2.2+

## Installation

### Gradle

Add the LinkLab SDK to your project by including it in your app's `build.gradle.kts` file:

```kotlin
dependencies {
    implementation("cc.linklab:android:0.0.1-SNAPSHOT")
}
```

Make sure you have the Maven Central repository in your project's `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        // For SNAPSHOT versions
        maven {
            url = uri("https://s01.oss.sonatype.org/content/repositories/snapshots/")
        }
    }
}
```

## Usage

### Initialization

Initialize the LinkLab SDK in your application or main activity:

```kotlin
// Initialize LinkLab
LinkLab.getInstance(context)
    .configure("your_api_key_here")
    .addListener(this)
```

This initialization will automatically check for install referrer information if the app is being opened for the first time after installation. If the app was installed through a LinkLab link that contains a `linklab_id` parameter, it will retrieve the link details.

### Processing Dynamic Links

To process dynamic links that launch your app, implement the `LinkLab.LinkLabListener` interface and handle the callbacks:

```kotlin
class MainActivity : AppCompatActivity(), LinkLab.LinkLabListener {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize LinkLab
        LinkLab.getInstance(this)
            .configure("your_api_key_here")
            .addListener(this)

        // Process any dynamic links that launched the app
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        // Check if the app was launched from a dynamic link
        if (LinkLab.getInstance(this).processDynamicLink(intent)) {
            // The link is being processed, we'll get the result in the callback
            Log.d(TAG, "Processing dynamic link from intent")
        } else {
            // This was not a dynamic link, handle normal deep linking if needed
            intent.data?.let { deepLink ->
                handleDeepLink(deepLink)
            }
        }
    }

    // Implement LinkLabListener methods
    override fun onDynamicLinkRetrieved(fullLink: Uri, data: LinkLab.LinkData) {
        // Handle the retrieved dynamic link
        Log.d(TAG, "Dynamic link retrieved: $fullLink")
        
        // Now you can navigate to the appropriate screen based on the link
        handleFullLink(fullLink, data)
    }

    override fun onError(exception: Exception) {
        // Handle errors
        Log.e(TAG, "Error handling dynamic link", exception)
    }
}
```

### Getting a Dynamic Link Programmatically

You can also retrieve a full link from a short link programmatically:

```kotlin
val shortLinkUri = Uri.parse("https://linklab.cc/abcd1234")
LinkLab.getInstance(context).getDynamicLink(shortLinkUri)
```

### Handling Different Link Types

You can route to different parts of your app based on the link path:

```kotlin
private fun handleFullLink(fullLink: Uri, data: LinkLab.LinkData) {
    // Example of handling different types of links
    val path = fullLink.path

    when {
        path?.startsWith("/product/") == true -> {
            val productId = path.substring("/product/".length)
            openProductDetails(productId)
        }
        path?.startsWith("/category/") == true -> {
            val category = path.substring("/category/".length)
            openCategoryScreen(category)
        }
        else -> {
            // Default handling
            openMainScreen()
        }
    }
}
```

## Install Referrer Integration

The SDK automatically integrates with the Google Play Install Referrer API to track app installs from LinkLab links. When a user installs your app through a LinkLab link, the SDK will:

1. Retrieve the install referrer information from Google Play
2. Extract the `linklab_id` parameter from the referrer URL
3. Fetch the full link details from the LinkLab API
4. Deliver the results through the same listener interface

This allows you to attribute app installs to specific LinkLab marketing campaigns and provide a personalized onboarding experience based on the link that led to the installation.

### How It Works

1. When a user clicks on this link and installs your app from Google Play, the install referrer information is stored
2. When your app launches for the first time, the SDK automatically checks for install referrer information
3. If the referrer is linklab the SDK fetches the full link details
4. Your app receives the same callback as if the user had opened a dynamic link directly

## Sample App

This repository includes a sample app that demonstrates how to use the LinkLab SDK. To run the sample app:

1. Clone this repository
2. Open the project in Android Studio
3. Replace `your_api_key_here` in `MainActivity.kt` with your LinkLab API key
4. Run the sample app on your device

The sample app demonstrates both direct deep linking and install referrer handling.

## License

This project is licensed under the Apache License, Version 2.0. See the LICENSE file for details.

## Publishing to Maven Central

The library uses the Nexus Publish Plugin to deploy to Maven Central. For detailed instructions, see [PUBLISHING.md](PUBLISHING.md).

Quick guide:

1. Setup your Sonatype OSSRH account and GPG key
2. Add credentials to `~/.gradle/gradle.properties`:

```properties
ossrhUsername=your_sonatype_username
ossrhPassword=your_sonatype_password

# For GPG signing
signing.keyId=your_gpg_key_id_last_8_chars
signing.password=your_gpg_key_password
signing.secretKeyRingFile=/path/to/your/gpg/secring.gpg
```

3. Publish with:

```bash
# For SNAPSHOT versions
./gradlew publishToMavenCentral

# For release versions (publishes and releases)
./gradlew publishAndRelease
```

## Support

For questions or support, please contact support@linklab.cc or visit our website at [https://linklab.cc](https://linklab.cc)