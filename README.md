# LinkLab Android SDK

The LinkLab Android SDK makes it easy to implement dynamic links in your Android application. This SDK allows you to create, manage, and track deep links that work across platforms and provide a seamless user experience.

## Features

- Process dynamic links that launch your app
- Retrieve full links from short links
- Handle custom deep link routing
- Automatically track dynamic link analytics

## Requirements

- Android SDK 21+
- Kotlin 2.0+
- OkHttp 4.11.0+

## Installation

### Gradle

Add the LinkLab SDK to your project by including it in your app's `build.gradle.kts` file:

```kotlin
dependencies {
    implementation("cc.linklab:android:1.0.0")
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

## Sample App

This repository includes a sample app that demonstrates how to use the LinkLab SDK. To run the sample app:

1. Clone this repository
2. Open the project in Android Studio
3. Replace `your_api_key_here` in `MainActivity.kt` with your LinkLab API key
4. Run the sample app on your device

## License

This project is licensed under the Apache License, Version 2.0. See the LICENSE file for details.

## Support

For questions or support, please contact support@linklab.cc or visit our website at [https://linklab.cc](https://linklab.cc)