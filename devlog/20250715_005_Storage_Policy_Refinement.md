# Development Log: Storage Policy Refinement

**Date:** 2025-07-15  
**Session:** 04  
**Developer:** Claude (AI Assistant)  
**Issue Type:** Architecture & Planning

## 1. Project Overview

This session focused on defining a clear and future-proof strategy for file storage, addressing the security and user experience issues raised in `IMPROVEMENT_REPORT.md`. The primary goal was to eliminate the dependency on the sensitive `MANAGE_EXTERNAL_STORAGE` permission.

## 2. Initial Plan: Storage Access Framework (SAF)

An initial detailed plan was formulated and documented in `docs/STORAGE_IMPROVEMENT_PLAN.md`. This plan proposed a multi-stage approach:

1.  **Isolate Web Uploads:** Move files uploaded via the web server to an app-specific directory.
2.  **Introduce SAF:** Implement the Storage Access Framework (`ACTION_OPEN_DOCUMENT`, `ACTION_OPEN_DOCUMENT_TREE`) to allow users to explicitly grant access to PDF files or folders.
3.  **Migrate to URIs:** Update the database and file handling logic to use `content://` URIs instead of direct file paths.
4.  **Deprecate Legacy Code:** Remove all code related to `MANAGE_EXTERNAL_STORAGE` and direct `Download` folder scanning.

## 3. Final Decision: Simplification to App-Specific Storage

After a review of the initial plan, a strategic decision was made to adopt a simpler, more robust, and more secure model. The complexity of implementing SAF and managing persistent URI permissions was deemed unnecessary for the app's core use case.

**The final, simpler plan was documented in `docs/APP_SPECIFIC_STORAGE_PLAN.md`.**

### Key Principles of the Final Plan:

*   **Single Source of Truth:** All PDF files will be managed exclusively through the app's web upload interface and stored in the app-specific directory (`context.getExternalFilesDir()`).
*   **Zero Permissions:** The app will require **no** storage permissions from the user, completely eliminating friction upon installation.
*   **User-Managed Migration:** Existing users will be guided to re-upload their files via the web interface, ensuring a clean and explicit library rebuild.
*   **Code Simplification:** All complex logic for handling permissions, SAF, and legacy file scanning will be removed, resulting in a much cleaner and more maintainable codebase.

## 4. Rationale for the Pivot

*   **Enhanced Security:** By never accessing storage outside its own sandboxed directory, the app provides the strongest possible privacy protection for users.
*   **Improved User Experience:** Eliminating all permission requests creates a seamless onboarding experience.
*   **Reduced Complexity:** The final plan is significantly easier to implement and maintain, reducing potential bugs.
*   **Guaranteed Policy Compliance:** This approach is perfectly aligned with Google Play's evolving storage policies, ensuring future compatibility.

## 5. Outcome

Two detailed planning documents were produced, capturing the evolution of the strategy. The project now has a clear, actionable, and simplified plan to resolve its storage-related issues, prioritizing security and user experience.
