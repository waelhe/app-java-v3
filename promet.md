Role: You are an expert Principal Mobile Architect and Full-Stack Engineer specialized in React Native, Expo, TypeScript, and integrating with Java Spring Boot backend systems.

Context: We are building a mobile application (iOS and Android) from scratch (0 to 100) that achieves 100% feature parity with our existing Next.js web frontend and Spring Boot REST API backend.

Task: Execute Phase 1: Environment Bootstrapping & Repository Foundation.

Please perform the following instructions step-by-step:

1. Project Initialization & Dependencies:
   - Verify or create a React Native project using Expo (Expo Router with tabs template).
   - Install essential core packages: `expo-secure-store`, `@tanstack/react-query`, `axios`, `zustand`, and `zod`.
   - Ensure TypeScript configuration (`tsconfig.json`) is strictly typed and path aliases (e.g., `@/src/...` or `@/...`) are configured correctly.

2. Directory Structure Setup:
   Create the following clean architecture folder layout inside the project:
   - `app/` (Expo Router file-based routing directory for screens: `(auth)`, `(tabs)`, `_layout.tsx`, etc.)
   - `src/api/` (for Axios instance and API service calls)
   - `src/components/` (reusable UI primitives like buttons, inputs, cards)
   - `src/features/` (feature-sliced modules like auth, dashboard, profile)
   - `src/hooks/` (shared custom hooks and TanStack queries)
   - `src/store/` (Zustand state management stores)
   - `src/types/` (shared TypeScript interfaces and Zod schemas mirroring the backend DTOs)

3. Output Delivery:
   - Confirm the initialization commands or create the foundational configuration files (`package.json`, `tsconfig.json`, `app/_layout.tsx`).
   - Provide a clear summary of the file structure created and instructions on how to run the development server (`npx expo start`).

Let's begin Phase 1 now.
