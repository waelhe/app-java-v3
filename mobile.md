إليك خطة تنفيذية هندسية متكاملة ومنظمة (Master Blueprint) مصممة بلغة هيكلية دقيقة (Prompt-Ready) يفهمها أي مساعد ذكاء اصطناعي (AI Coding Agent مثل Claude Code أو Cursor أو OpenCode) لتنفيذ بناء تطبيق الجوال من الصفر إلى النشر الكامل، مع الاستفادة القصوى من كود Next.js الموجود وموقع الـ Spring Boot + Maven للباك إند.

[MASTER_SYSTEM_BLUEPRINT] Mobile App Development Execution Plan

0. Project Context & Environment Directive

Backend: Java 25, Spring Boot 4.x, Maven, REST APIs, Spring Security (JWT), Springdoc OpenAPI.

Frontend Web: Next.js (App Router, TypeScript, Tailwind CSS, TanStack Query, Zod validation).

Target Mobile Stack: React Native (Expo SDK, Expo Router, TypeScript, NativeWind/Tailwind, Zustand, TanStack Query, Axios).

Core Objective: Achieve 100% Feature Parity with the Next.js frontend, utilizing the existing Spring Boot REST endpoints seamlessly.

Phase 1: Environment Bootstrapping & Repository Foundation

Goal: Initialize the React Native workspace, configure design tokens, and establish TypeScript/API contracts from Next.js & Spring Boot.

Step 1.1: Project Initialization

Run npx create-expo-app@latest mobile-app --template tabs using Expo Router.

Install core dependencies: npx expo install expo-secure-store @tanstack/react-query axios zustand zod.

Step 1.2: OpenAPI Contract Synchronization

Fetch v3/api-docs or openapi.json from the running Spring Boot server.

Use OpenAPI Generator to automatically generate TypeScript DTO types matching the backend Java Records/Entities, or mirror them from existing Next.js types/ directory.

Step 1.3: Architectural Directory Layout Setup

Create folder structure:mobile-app/ ├── app/ # Expo Router file-based screens ├── src/ │ ├── api/ # Axios instance, interceptors, endpoints │ ├── components/ # Reusable UI primitives (buttons, inputs, cards) │ ├── features/ # Feature-sliced modules (auth, dashboard, profile) │ ├── hooks/ # Shared custom hooks and TanStack queries │ ├── store/ # Zustand global state (auth store, settings) │ └── types/ # Shared TypeScript interfaces & Zod schemas 

Phase 2: Core Networking & Security Layer (Spring Boot Integration)

Goal: Implement secure authentication, token management, and global error handling with Spring Security.

Step 2.1: Secure Storage & Auth State Management

Implement an auth wrapper using expo-secure-store for storing accessToken and refreshToken.

Build a Zustand useAuthStore handling isAuthenticated, userProfile, login(), and logout().

Step 2.2: Axios HTTP Client & Interceptors

Create src/api/client.ts using Axios pointing to the Spring Boot base URL.

Request Interceptor: Automatically attach Authorization: Bearer <accessToken> to outgoing requests.

Response Interceptor: Catch 401 Unauthorized responses, trigger a refresh token request to Spring Boot, queue failed requests, and retry seamlessly.

Step 2.3: Global Error Boundary & Notification Toasts

Parse Spring Boot ProblemDetail or standard validation exception responses (MethodArgumentNotValidException) and display clean, localized toast messages on the mobile screen.

Phase 3: Feature Parity Implementation (Next.js Code Migration)

Goal: Port business logic, forms, data hooks, and screens from the Next.js frontend to React Native components.

Step 3.1: Authentication Screens Migration

Build Login, Register, and Forgot Password screens using React Native primitives (View, Text, TextInput, TouchableOpacity).

Reuse or rewrite validation logic using Zod schemas identical to the Next.js frontend.

Step 3.2: Layout, Navigation & Deep Linking

Map Next.js App Router folders directly to Expo Router file paths (app/(tabs)/index.tsx, app/(tabs)/explore.tsx, etc.).

Configure Universal Links / Deep Links so web URLs route natively inside the app.

Step 3.3: Dashboard & Core Data Modules

Migrate API service functions from Next.js to mobile services.

Wrap API calls with TanStack Query (useQuery, useMutation) for automated caching, background refetching, and optimistic updates identical to the web app.

Replace HTML elements with native counterparts (<div> \rightarrow View, <span> \rightarrow Text, <img> \rightarrow Image or expo-image).

Phase 4: Native Capabilities & Offline Optimization

Goal: Integrate hardware features and performance optimizations unavailable on standard web browsers.

Step 4.1: Push Notifications (Firebase Cloud Messaging - FCM)

Install expo-notifications.

Integrate Firebase Admin SDK on the Spring Boot backend to register device tokens when users log in.

Handle incoming background and foreground push notifications.

Step 4.2: Local Caching & Offline Mode

Implement MMKV or SQLite storage for caching frequently accessed API responses to allow offline data browsing.

Step 4.3: Native UI/UX Refinements

Implement safe area padding (react-native-safe-area-context), keyboard handling (react-native-keyboard-controller), and smooth loading skeletons.

Phase 5: Testing, Quality Assurance & Deployment

Goal: Validate performance, fix cross-platform bugs, and publish to app stores.

Step 5.1: Device Testing & Debugging

Test on physical iOS and Android devices using Expo Go and Development Builds (npx expo run:android / npx expo run:ios).

Verify token refresh loops, network throttling scenarios, and memory leaks.

Step 5.2: CI/CD & Build Pipelines

Configure EAS Build (Expo Application Services) to generate production bundles (.aab for Android and .ipa for iOS).

Step 5.3: Store Deployment

Publish internal testing tracks via Google Play Console and Apple App Store Connect (TestFlight).

