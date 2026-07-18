# Context-Aware Assistant 🧠✨

A highly continuous, memory-based AI chat assistant built for Android using Kotlin, Jetpack Compose (Material 3), and Room. The application auto-extracts personal facts, preferences, and details from active chat conversations, maintaining an offline-first **Memory Bank** that persists across sessions to personalize all future responses dynamically.

---

## 🎬 Key Features

1. **Context-Aware Conversational Continuity**: The app appends all learned background information about the user to the Gemini API system instructions on every turn. This allows the AI model to remember past facts naturally without having to repeatedly prompt the user.
2. **Automated Memory Extraction**: In the background, a secondary asynchronous parsing routine analyzes conversations after each assistant completion to identify and store key user preferences.
3. **Interactive Memory Bank UI**: Users can view all learned facts, delete obsolete or incorrect facts, and manually add custom preferences (e.g., teaching the assistant their diet, favorite coding languages, or pets' names).
4. **Offline-First Room Persistence**: All chat threads, message histories, and user profile memories are stored in a local SQLite database via Room, ensuring instant startup and persistent local context.
5. **Multi-Thread Chat Hub**: Create, rename, or delete separate conversation topics (e.g., Coding Helper, Cooking Guide, Workout Coach) with slide-out Navigation Drawer management.
6. **Edge-to-Edge Design**: Full compliance with Material Design 3 guidelines featuring a stunning Dark Slate theme, custom floating cards, fluid transition animations, and Safe Drawing Window insets.

---

## 🏗️ Technical Architecture

### 🗄️ Room Database Schema
- **`ConversationEntity`**: Holds metadata for individual chat sessions.
- **`MessageEntity`**: Holds individual chat history bubbles, including the message `role` (`"user"` or `"model"`), text, and timestamp.
- **`MemoryEntity`**: Holds the long-term preferences and facts extracted about the user (e.g., *"User's dog is named Sparky"*).

### 🤖 Gemini Integration & Prompt Continuity Flow
The application calls the Google Gemini API directly using secure REST calls:
```
POST https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent?key=${API_KEY}
```

Every request builds a rich system instruction block dynamically:
```kotlin
val systemPrompt = buildString {
    append("You are a helpful, context-aware AI assistant who maintains a continuous memory of the user... ")
    append("Here is the memory bank you have compiled about this user:\n")
    memories.forEach { mem -> append("- ${mem.content}\n") }
    append("\nRules: Naturally integrate these facts; DO NOT explicitly list them out unless asked.")
}
```

---

## 🚀 Setup & API Key Configuration

To ensure your API keys are protected and not hardcoded into source files, this project uses the **Secrets Gradle Plugin** and parses variables from a `.env` file generated at runtime.

### Step 1: Set Up API Key in AI Studio Secrets Panel
1. Open **Google AI Studio**.
2. Locate the **Secrets Panel** (usually on the sidebar or settings menu).
3. Add a new secret named `GEMINI_API_KEY`.
4. Paste your secure Gemini API Key into the value field. The platform will automatically inject this key into the workspace's `.env` configuration file at compile time.

> ⚠️ **Warning**: Never check in or hardcode actual API keys in `MainActivity.kt`, `GeminiService.kt`, or configuration files.

---

## 🛠️ Compilation and Run Instructions

### To Compile and Install the App
Since this is an incremental build platform, you can compile and preview your code instantly using AI Studio build tools:

1. Click **Compile Applet** or build the debugger inside AI Studio.
2. The compiler resolves dependencies (`Room`, `Retrofit`, `OkHttp`) automatically and flashes the Streaming Android Emulator in your browser.
3. If doing terminal command builds, run:
   ```bash
   gradle :app:assembleDebug
   ```

---

## 📝 User Journey & Usage Examples

### 1. Registering a Fact (Explicit or Conversational)
- **User Says**: *"I prefer writing in Kotlin rather than Java, and my dog is named Rex."*
- **AI Completes**: *"Got it! I will remember that you code in Kotlin and have a dog named Rex."*
- **Behind the Scenes**: The background task identifies these facts and saves two new rows in the `memories` database:
  - `Kotlin is preferred over Java`
  - `User has a dog named Rex`

### 2. Testing the Continuity (Later / in another thread)
- **User Says**: *"Can you recommend a basic project to build this weekend?"*
- **AI Completes**: *"Since you prefer coding in Kotlin, why not build a simple Android app tracker? You could even add a feature dedicated to tracking Rex's feeding schedule!"*

---

## 🔒 Security Notice

> ⚠️ **Security Warning**: I have configured your API keys for injecting via `BuildConfig` using the Secrets Gradle Plugin for this prototype. Please be aware that Android APKs can be easily decompiled, and these keys can be extracted by anyone who has access to the file. **Do not share the generated APK file publicly or with unauthorized individuals** to prevent potential misuse.
