# Cloudinary Image Upload Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Allow admins to upload product images via a file picker that uploads to Cloudinary and auto-fills the imageUrl field.

**Architecture:** Browser sends the file to `POST /api/upload` (ADMIN only). The backend forwards it to Cloudinary via the SDK and returns the secure URL. The frontend replaces the imageUrl text input with a file picker — on file select it calls the upload endpoint, gets the URL back, and stores it in the form state plus shows a preview.

**Tech Stack:** Cloudinary Java SDK (`cloudinary-http44`), Spring Boot `MultipartFile`, Vue 3 + Axios

---

### Task 1: Add Cloudinary SDK to pom.xml

**Files:**
- Modify: `pom.xml`

- [ ] **Step 1: Add the dependency**

In `pom.xml`, add inside `<dependencies>`:

```xml
<dependency>
    <groupId>com.cloudinary</groupId>
    <artifactId>cloudinary-http44</artifactId>
    <version>1.39.0</version>
</dependency>
```

- [ ] **Step 2: Verify Maven resolves it**

```bash
./mvnw dependency:resolve -q
```

Expected: BUILD SUCCESS with no errors.

- [ ] **Step 3: Commit**

```bash
git add pom.xml
git commit -m "chore: add cloudinary-http44 dependency"
```

---

### Task 2: Add Cloudinary config to application.yml

**Files:**
- Modify: `src/main/resources/application.yml`

- [ ] **Step 1: Add Cloudinary credentials block**

Append to the bottom of `application.yml` under the `app:` section:

```yaml
  cloudinary:
    cloud-name: YOUR_CLOUD_NAME
    api-key: YOUR_API_KEY
    api-secret: YOUR_API_SECRET
```

So the full `app:` block looks like:

```yaml
app:
  jwt:
    secret: your-256-bit-secret-key-replace-this-in-production-please
    access-expiration-ms: 300000
    refresh-expiration-ms: 86400000
  order:
    pending-timeout-minutes: 15
    expiry-job-interval-seconds: 60
    expiry-job-batch-size: 100
  cloudinary:
    cloud-name: YOUR_CLOUD_NAME
    api-key: YOUR_API_KEY
    api-secret: YOUR_API_SECRET
```

Replace `YOUR_CLOUD_NAME`, `YOUR_API_KEY`, `YOUR_API_SECRET` with the values from your Cloudinary dashboard.

- [ ] **Step 2: Commit**

```bash
git add src/main/resources/application.yml
git commit -m "chore: add cloudinary config to application.yml"
```

---

### Task 3: Create CloudinaryService

**Files:**
- Create: `src/main/java/com/example/flashsale/service/CloudinaryService.java`

- [ ] **Step 1: Create the service**

```java
package com.example.flashsale.service;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

@Service
public class CloudinaryService {

    private final Cloudinary cloudinary;

    public CloudinaryService(
            @Value("${app.cloudinary.cloud-name}") String cloudName,
            @Value("${app.cloudinary.api-key}") String apiKey,
            @Value("${app.cloudinary.api-secret}") String apiSecret) {
        this.cloudinary = new Cloudinary(ObjectUtils.asMap(
                "cloud_name", cloudName,
                "api_key", apiKey,
                "api_secret", apiSecret,
                "secure", true
        ));
    }

    public String upload(MultipartFile file) throws IOException {
        Map<?, ?> result = cloudinary.uploader().upload(file.getBytes(), ObjectUtils.asMap(
                "folder", "flash-sale/products"
        ));
        return (String) result.get("secure_url");
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/com/example/flashsale/service/CloudinaryService.java
git commit -m "feat: add CloudinaryService for image upload"
```

---

### Task 4: Create UploadController

**Files:**
- Create: `src/main/java/com/example/flashsale/controller/UploadController.java`

- [ ] **Step 1: Create the controller**

```java
package com.example.flashsale.controller;

import com.example.flashsale.service.CloudinaryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/upload")
@RequiredArgsConstructor
public class UploadController {

    private final CloudinaryService cloudinaryService;

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping
    public ResponseEntity<Map<String, String>> upload(@RequestParam("file") MultipartFile file) throws IOException {
        String url = cloudinaryService.upload(file);
        return ResponseEntity.ok(Map.of("url", url));
    }
}
```

- [ ] **Step 2: Add multipart config to application.yml** (if not already present)

Under `spring:` add:

```yaml
  servlet:
    multipart:
      max-file-size: 5MB
      max-request-size: 5MB
```

- [ ] **Step 3: Rebuild and test manually with Postman**

- Start the backend: `./mvnw spring-boot:run`
- Login as admin, copy the access token
- In Postman: `POST http://localhost:8080/api/upload`
  - Authorization: `Bearer <token>`
  - Body: form-data, key=`file` (type=File), value=any image file
- Expected response:
```json
{ "url": "https://res.cloudinary.com/YOUR_CLOUD/image/upload/v.../flash-sale/products/....jpg" }
```

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/example/flashsale/controller/UploadController.java src/main/resources/application.yml
git commit -m "feat: add POST /api/upload endpoint (ADMIN only)"
```

---

### Task 5: Update frontend ProductsView.vue

**Files:**
- Modify: `frontend/src/views/admin/ProductsView.vue`

- [ ] **Step 1: Replace the imageUrl text input with a file picker**

Replace this block in the `<template>`:

```html
<input v-model="form.imageUrl" placeholder="Image URL (optional)"
  class="border border-gray-200 rounded-xl px-3.5 py-2.5 text-sm outline-none focus:ring-2 focus:ring-blue-600 transition bg-white" />
```

With:

```html
<div class="flex flex-col gap-2">
  <input type="file" accept="image/*" @change="handleImageUpload"
    class="border border-gray-200 rounded-xl px-3.5 py-2.5 text-sm bg-white cursor-pointer" />
  <img v-if="form.imageUrl" :src="form.imageUrl" alt="Preview"
    class="h-24 w-24 object-cover rounded-xl border border-gray-200" />
  <p v-if="uploadingImage" class="text-xs text-gray-400">Uploading…</p>
  <p v-if="uploadError" class="text-xs text-red-500">{{ uploadError }}</p>
</div>
```

- [ ] **Step 2: Add upload state and handler in `<script setup>`**

Add these refs after the existing ones (`creating`, `createError`):

```js
const uploadingImage = ref(false)
const uploadError = ref('')
```

Add this function before `createProduct`:

```js
async function handleImageUpload(event) {
  const file = event.target.files[0]
  if (!file) return
  uploadingImage.value = true
  uploadError.value = ''
  try {
    const formData = new FormData()
    formData.append('file', file)
    const res = await api.post('/upload', formData, {
      headers: { 'Content-Type': 'multipart/form-data' }
    })
    form.imageUrl = res.data.url
  } catch {
    uploadError.value = 'Image upload failed.'
  } finally {
    uploadingImage.value = false
  }
}
```

- [ ] **Step 3: Reset imageUrl and uploadError on successful product creation**

In `createProduct`, the `Object.assign` reset line already resets `form.imageUrl` to `''`. Also reset `uploadError`:

```js
Object.assign(form, { name: '', categoryId: '', price: '', initialStock: '', imageUrl: '', description: '' })
uploadError.value = ''
```

- [ ] **Step 4: Test in browser**

1. Start frontend: `npm run dev` in `frontend/`
2. Login as admin, go to Admin → Products
3. In the New Product form, click the file input and select an image
4. Verify "Uploading…" appears briefly, then a preview thumbnail shows
5. Fill in other fields and click Create Product
6. Verify the product appears in the list (check the imageUrl was saved by inspecting the product in DB or API response)

- [ ] **Step 5: Commit**

```bash
git add frontend/src/views/admin/ProductsView.vue
git commit -m "feat: replace imageUrl text input with Cloudinary file upload picker"
```
