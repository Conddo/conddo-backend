# Conddo.io — Pharmacy Module API Endpoint Spec

**Document type:** Backend Engineering Spec  
**Audience:** Backend Team  
**Tenant reference:** Seb&Bayor Pharmaceutical (first pharmacy tenant)  
**Last updated:** June 2026  
**Status:** Active — implement before Seb&Bayor integration begins

---

## Overview

This document defines every API endpoint the Pharmacy Module must expose. There are two categories:

- **Public Tenant API** — called by the tenant's website on behalf of customers. Authenticated by Site API Key.
- **Dashboard API** — called by the Conddo business dashboard on behalf of the pharmacist/admin. Authenticated by tenant JWT.

All endpoints are scoped to a specific tenant via `{slug}`. No endpoint ever returns data belonging to another tenant.

---

## Base URLs

```
Public API:    https://api.conddo.io/api/v1/public/{slug}
Dashboard API: https://api.conddo.io/api/v1/dashboard/{slug}
```

---

## Authentication

### Public API (Website)
All public endpoints require the site's API key in the request header:
```
X-Conddo-Site-Key: [tenant-site-api-key]
```

### Dashboard API (Pharmacist)
All dashboard endpoints require a valid tenant staff JWT:
```
Authorization: Bearer [tenant-jwt-token]
```

### Customer Auth (Website users)
Customers who create accounts on the website authenticate with their own JWT, issued by Conddo and scoped to that tenant:
```
Authorization: Bearer [customer-jwt-token]
```

---

## Error Response Format

All errors return a consistent shape:

```json
{
  "error": "ERROR_CODE",
  "message": "Human readable message",
  "field": "field_name"
}
```

Common HTTP status codes used:
- `400` Bad Request — missing or invalid fields
- `401` Unauthorized — missing or invalid auth
- `403` Forbidden — authenticated but not permitted
- `404` Not Found
- `409` Conflict — e.g. duplicate slug
- `500` Internal Server Error

---

## Section 1 — Store Info

### `GET /public/{slug}/store-info`

Returns the pharmacy's public profile. Called on every page load of the website.

**Auth:** Site API Key  
**Response:**
```json
{
  "store": {
    "name": "Seb & Bayor Pharmaceutical",
    "slug": "seb-bayorpharmaceutical",
    "logo": "https://cdn.conddo.io/tenants/seb-bayor/logo.png",
    "tagline": "Your trusted pharmacy partner",
    "phone": "08012345678",
    "whatsapp": "2348012345678",
    "email": "info@sebandbayor.com.ng",
    "address": "14 Balogun Street, Lagos",
    "state": "Lagos",
    "openingHours": {
      "monday": "8:00 AM - 8:00 PM",
      "tuesday": "8:00 AM - 8:00 PM",
      "wednesday": "8:00 AM - 8:00 PM",
      "thursday": "8:00 AM - 8:00 PM",
      "friday": "8:00 AM - 8:00 PM",
      "saturday": "9:00 AM - 6:00 PM",
      "sunday": "Closed"
    },
    "socials": {
      "instagram": "https://instagram.com/sebbayorpharma",
      "facebook": null
    },
    "activeModules": ["pharmacy"],
    "plan": "growth"
  }
}
```

---

## Section 2 — Customer Auth (Website)

These endpoints allow customers to create accounts and log in on the tenant's website. Customer accounts are scoped to the tenant — a customer on Seb&Bayor cannot log into another pharmacy's website with the same credentials.

---

### `POST /public/{slug}/auth/register`

**Auth:** Site API Key  
**Request body:**
```json
{
  "fullName": "Sarah Okafor",
  "email": "sarah@email.com",
  "phone": "08098765432",
  "password": "securepassword"
}
```

**Response `201`:**
```json
{
  "success": true,
  "token": "customer-jwt-token",
  "customer": {
    "id": "uuid",
    "fullName": "Sarah Okafor",
    "email": "sarah@email.com",
    "phone": "08098765432"
  }
}
```

**Errors:**
- `409` — email already registered for this tenant

---

### `POST /public/{slug}/auth/login`

**Auth:** Site API Key  
**Request body:**
```json
{
  "email": "sarah@email.com",
  "password": "securepassword"
}
```

**Response `200`:**
```json
{
  "success": true,
  "token": "customer-jwt-token",
  "customer": {
    "id": "uuid",
    "fullName": "Sarah Okafor",
    "email": "sarah@email.com",
    "phone": "08098765432"
  }
}
```

---

### `GET /public/{slug}/auth/me`

Returns the currently authenticated customer's profile.

**Auth:** Site API Key + Customer JWT  
**Response `200`:**
```json
{
  "customer": {
    "id": "uuid",
    "fullName": "Sarah Okafor",
    "email": "sarah@email.com",
    "phone": "08098765432",
    "createdAt": "2026-01-15T10:30:00Z"
  }
}
```

---

### `POST /public/{slug}/auth/forgot-password`

**Auth:** Site API Key  
**Request body:**
```json
{ "email": "sarah@email.com" }
```

**Response `200`:**
```json
{ "success": true, "message": "Reset link sent if email exists" }
```

Conddo sends the reset email via Brevo.

---

### `POST /public/{slug}/auth/reset-password`

**Auth:** Site API Key  
**Request body:**
```json
{
  "token": "reset-token-from-email",
  "password": "newpassword"
}
```

**Response `200`:**
```json
{ "success": true }
```

---

## Section 3 — Products (Public)

### `GET /public/{slug}/pharmacy/products`

Returns the pharmacy's active product catalogue.

**Auth:** Site API Key  
**Query params:**

| Param | Type | Description |
|---|---|---|
| `category` | string | Filter by category slug |
| `q` | string | Search by name or description |
| `featured` | boolean | Return only featured products (limit 8) |
| `requiresPrescription` | boolean | Filter prescription-only products |
| `page` | integer | Page number (default 1) |
| `limit` | integer | Results per page (default 20, max 50) |

**Response `200`:**
```json
{
  "products": [
    {
      "id": "uuid",
      "nameGeneric": "Amoxicillin",
      "nameBrand": "Amoxil",
      "slug": "amoxicillin-500mg",
      "description": "Broad-spectrum antibiotic",
      "indications": "Bacterial infections",
      "dosageGuidance": "500mg three times daily",
      "warnings": "Do not use if allergic to penicillin",
      "storage": "Store below 25°C",
      "price": 1500.00,
      "requiresPrescription": true,
      "stockQty": 48,
      "nafdacNumber": "A4-1234",
      "brand": "GSK",
      "images": [
        "https://cdn.conddo.io/tenants/seb-bayor/products/amoxicillin.jpg"
      ],
      "category": {
        "name": "Prescription Drugs",
        "slug": "prescription"
      },
      "isActive": true
    }
  ],
  "pagination": {
    "page": 1,
    "limit": 20,
    "total": 84,
    "pages": 5
  }
}
```

---

### `GET /public/{slug}/pharmacy/products/{productSlug}`

Returns a single product's full details.

**Auth:** Site API Key  
**Response `200`:** Same shape as single product object above.  
**Error:** `404` if product not found or inactive.

---

### `GET /public/{slug}/pharmacy/categories`

Returns all active product categories for this pharmacy.

**Auth:** Site API Key  
**Response `200`:**
```json
{
  "categories": [
    {
      "id": "uuid",
      "name": "Prescription Drugs",
      "slug": "prescription",
      "icon": "pill",
      "productCount": 32
    },
    {
      "id": "uuid",
      "name": "OTC Medications",
      "slug": "otc",
      "icon": "capsule",
      "productCount": 18
    }
  ]
}
```

---

## Section 4 — Cart

Cart is stored server-side per customer session.

---

### `GET /public/{slug}/pharmacy/cart`

**Auth:** Site API Key + Customer JWT  
**Response `200`:**
```json
{
  "cart": {
    "items": [
      {
        "productId": "uuid",
        "nameGeneric": "Amoxicillin",
        "nameBrand": "Amoxil",
        "price": 1500.00,
        "quantity": 2,
        "requiresPrescription": true,
        "image": "https://cdn.conddo.io/..."
      }
    ],
    "subtotal": 3000.00,
    "itemCount": 2
  }
}
```

---

### `POST /public/{slug}/pharmacy/cart`

Add or update an item in the cart. If item exists, quantity is replaced (not added).

**Auth:** Site API Key + Customer JWT  
**Request body:**
```json
{
  "productId": "uuid",
  "quantity": 2
}
```

**Response `200`:** Returns updated cart (same shape as GET).  
**Errors:**
- `400` — quantity exceeds stock
- `404` — product not found or inactive

---

### `DELETE /public/{slug}/pharmacy/cart/{productId}`

Remove an item from the cart.

**Auth:** Site API Key + Customer JWT  
**Response `200`:** Returns updated cart.

---

### `DELETE /public/{slug}/pharmacy/cart`

Clear the entire cart.

**Auth:** Site API Key + Customer JWT  
**Response `200`:** `{ "success": true }`

---

## Section 5 — Orders (Public)

### `POST /public/{slug}/pharmacy/orders`

Place an order. Conddo validates stock, calculates delivery fee by state, creates the order, and sends confirmation via Brevo email and SMS.

**Auth:** Site API Key + Customer JWT  
**Request body:**
```json
{
  "items": [
    { "productId": "uuid", "quantity": 2 }
  ],
  "addressId": "uuid",
  "notes": "Please pack carefully",
  "prescriptionId": "uuid"
}
```

**Response `201`:**
```json
{
  "success": true,
  "order": {
    "id": "uuid",
    "status": "PENDING",
    "subtotal": 3000.00,
    "deliveryFee": 1500.00,
    "total": 4500.00,
    "paymentStatus": "PENDING",
    "paymentLink": "https://pay.routepay.com/xyz",
    "items": [...],
    "createdAt": "2026-06-06T10:00:00Z"
  }
}
```

**Notes for backend:**
- Validate stock at submission time, not at cart add time
- Delivery fee calculated by customer's state using Nigerian state fee table (see Seb&Bayor's `lib/delivery-fees.ts` for reference values)
- If any cart item `requiresPrescription` and no `prescriptionId` is provided, return `400` with `error: "PRESCRIPTION_REQUIRED"`
- Clear cart after successful order creation
- Send order confirmation via Brevo email and SMS (non-blocking)
- Generate Routepay payment link and attach to response

**Errors:**
- `400 PRESCRIPTION_REQUIRED` — order contains prescription drugs but no prescription attached
- `400 OUT_OF_STOCK` — one or more items no longer have sufficient stock
- `404` — product not found

---

### `GET /public/{slug}/pharmacy/orders`

Returns the authenticated customer's order history.

**Auth:** Site API Key + Customer JWT  
**Response `200`:**
```json
{
  "orders": [
    {
      "id": "uuid",
      "status": "DELIVERED",
      "paymentStatus": "PAID",
      "total": 4500.00,
      "itemCount": 2,
      "createdAt": "2026-06-01T10:00:00Z"
    }
  ]
}
```

---

### `GET /public/{slug}/pharmacy/orders/{orderId}`

Returns full details of a single order.

**Auth:** Site API Key + Customer JWT  
**Response `200`:**
```json
{
  "order": {
    "id": "uuid",
    "status": "PROCESSING",
    "paymentStatus": "PAID",
    "subtotal": 3000.00,
    "deliveryFee": 1500.00,
    "total": 4500.00,
    "notes": "Please pack carefully",
    "paymentLink": "https://pay.routepay.com/xyz",
    "items": [
      {
        "productId": "uuid",
        "nameGeneric": "Amoxicillin",
        "nameBrand": "Amoxil",
        "quantity": 2,
        "unitPrice": 1500.00,
        "lineTotal": 3000.00
      }
    ],
    "address": {
      "street": "14 Balogun Street",
      "city": "Lagos Island",
      "state": "Lagos"
    },
    "prescription": {
      "id": "uuid",
      "status": "APPROVED"
    },
    "createdAt": "2026-06-01T10:00:00Z",
    "updatedAt": "2026-06-01T11:30:00Z"
  }
}
```

**Errors:** `403` if order does not belong to the authenticated customer.

---

## Section 6 — Prescriptions (Public)

### `POST /public/{slug}/pharmacy/prescriptions`

Upload a prescription. File must already be uploaded to Conddo's MinIO storage and the URL passed here.

**Auth:** Site API Key + Customer JWT  
**Request body:**
```json
{
  "fileUrl": "https://cdn.conddo.io/tenants/seb-bayor/prescriptions/rx-001.jpg",
  "patientName": "Sarah Okafor",
  "prescriberName": "Dr. Emeka Obi",
  "notes": "For chronic hypertension management"
}
```

**Response `201`:**
```json
{
  "success": true,
  "prescription": {
    "id": "uuid",
    "status": "PENDING",
    "submittedAt": "2026-06-06T10:00:00Z"
  }
}
```

---

### `GET /public/{slug}/pharmacy/prescriptions`

Returns all prescriptions submitted by the authenticated customer.

**Auth:** Site API Key + Customer JWT  
**Response `200`:**
```json
{
  "prescriptions": [
    {
      "id": "uuid",
      "fileUrl": "https://cdn.conddo.io/...",
      "patientName": "Sarah Okafor",
      "prescriberName": "Dr. Emeka Obi",
      "status": "APPROVED",
      "reviewNote": "Valid. Dispense as prescribed.",
      "submittedAt": "2026-06-01T09:00:00Z",
      "reviewedAt": "2026-06-01T10:15:00Z",
      "orderId": "uuid"
    }
  ]
}
```

---

## Section 7 — Addresses (Public)

### `GET /public/{slug}/customer/addresses`

**Auth:** Site API Key + Customer JWT  
**Response `200`:**
```json
{
  "addresses": [
    {
      "id": "uuid",
      "label": "Home",
      "street": "14 Balogun Street",
      "city": "Lagos Island",
      "state": "Lagos",
      "landmark": "Beside GTBank",
      "isDefault": true,
      "deliveryFee": 1500,
      "deliveryEstimate": "2-4 hours (same day)"
    }
  ]
}
```

---

### `POST /public/{slug}/customer/addresses`

**Auth:** Site API Key + Customer JWT  
**Request body:**
```json
{
  "label": "Office",
  "street": "5 Marina Road",
  "city": "Lagos Island",
  "state": "Lagos",
  "landmark": "Beside First Bank",
  "isDefault": false
}
```

**Response `201`:** Returns created address with `deliveryFee` and `deliveryEstimate` calculated.

---

### `DELETE /public/{slug}/customer/addresses/{addressId}`

**Auth:** Site API Key + Customer JWT  
**Response `200`:** `{ "success": true }`

---

## Section 8 — Consultations (Public)

### `POST /public/{slug}/pharmacy/consultations`

Allows a customer to request a telepharmacy consultation. Conddo notifies the pharmacist via dashboard notification and Brevo SMS.

**Auth:** Site API Key (customer JWT optional — walk-ins can also book)  
**Request body:**
```json
{
  "whatsappNumber": "2348012345678",
  "topic": "Drug interaction query — I take Warfarin and want to know if I can take Ibuprofen",
  "preferredTime": "2026-06-07T10:00:00Z",
  "customerId": "uuid"
}
```

**Response `201`:**
```json
{
  "success": true,
  "consultation": {
    "id": "uuid",
    "status": "PENDING",
    "message": "Your consultation request has been received. A pharmacist will contact you on WhatsApp shortly."
  }
}
```

---

### `GET /public/{slug}/pharmacy/consultations/availability`

Returns available consultation slots for the next 7 days.

**Auth:** Site API Key  
**Response `200`:**
```json
{
  "slots": [
    {
      "date": "2026-06-07",
      "times": ["09:00", "10:00", "11:00", "14:00", "15:00"]
    },
    {
      "date": "2026-06-08",
      "times": ["09:00", "10:00"]
    }
  ]
}
```

---

## Section 9 — Articles / Blog (Public)

### `GET /public/{slug}/articles`

**Auth:** Site API Key  
**Query params:** `category`, `page`, `limit`  
**Response `200`:**
```json
{
  "articles": [
    {
      "id": "uuid",
      "title": "5 Things to Know About Antibiotic Resistance",
      "slug": "antibiotic-resistance-guide",
      "excerpt": "Antibiotic resistance is one of the biggest threats...",
      "coverImage": "https://cdn.conddo.io/...",
      "category": "Health Tips",
      "publishedAt": "2026-05-20T08:00:00Z"
    }
  ],
  "pagination": { "page": 1, "limit": 10, "total": 24, "pages": 3 }
}
```

---

### `GET /public/{slug}/articles/{articleSlug}`

Returns full article content.

**Auth:** Site API Key  
**Response `200`:**
```json
{
  "article": {
    "id": "uuid",
    "title": "5 Things to Know About Antibiotic Resistance",
    "slug": "antibiotic-resistance-guide",
    "content": "Full HTML or markdown content here...",
    "coverImage": "https://cdn.conddo.io/...",
    "category": "Health Tips",
    "author": { "name": "PharmD. Seb Adeniyi" },
    "publishedAt": "2026-05-20T08:00:00Z"
  }
}
```

---

## Section 10 — File Upload

### `POST /public/{slug}/upload`

Upload a file (prescription image, product image) to Conddo's MinIO storage. Returns a permanent URL.

**Auth:** Site API Key + Customer JWT  
**Request:** `multipart/form-data` with field `file`  
**Allowed types:** `image/jpeg`, `image/png`, `image/webp`, `application/pdf`  
**Max size:** 5MB  

**Response `200`:**
```json
{
  "url": "https://cdn.conddo.io/tenants/seb-bayor/uploads/rx-001.jpg"
}
```

---

## Section 11 — Delivery Fees (Public)

### `GET /public/{slug}/pharmacy/delivery-fee`

Returns the delivery fee and estimate for a given state.

**Auth:** Site API Key  
**Query params:** `state=Lagos`  
**Response `200`:**
```json
{
  "state": "Lagos",
  "fee": 1500,
  "estimate": "2-4 hours (same day)",
  "freeDeliveryThreshold": null
}
```

**Reference delivery fee table (from Seb&Bayor codebase):**

| State | Fee (₦) | Estimate |
|---|---|---|
| Lagos | 1,500 | 2-4 hours same day |
| Ogun, Oyo, Osun, Ondo, Ekiti | 2,500–3,000 | 1-2 business days |
| Rivers, Abuja, Delta, Edo, Enugu, Anambra, Imo | 3,000–3,500 | 2-3 business days |
| Kano, Kaduna, Plateau, Benue, Cross River, Akwa Ibom | 3,500–4,000 | 3-5 business days |
| Bayelsa, Taraba, Adamawa, Bauchi, Borno, Gombe, Yobe, Sokoto, Zamfara | 4,500–5,000 | 4-5 business days |
| Default (unlisted state) | 4,000 | 2-5 business days |

---

## Section 12 — Dashboard API (Pharmacist/Admin)

These endpoints power the Conddo business dashboard for the pharmacy tenant. All require tenant staff JWT.

---

### Products

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/dashboard/{slug}/pharmacy/products` | List all products (active + inactive) |
| `POST` | `/dashboard/{slug}/pharmacy/products` | Create a new product |
| `GET` | `/dashboard/{slug}/pharmacy/products/{id}` | Get single product |
| `PUT` | `/dashboard/{slug}/pharmacy/products/{id}` | Update product |
| `PATCH` | `/dashboard/{slug}/pharmacy/products/{id}/toggle` | Toggle active/inactive |
| `DELETE` | `/dashboard/{slug}/pharmacy/products/{id}` | Delete product |
| `GET` | `/dashboard/{slug}/pharmacy/products/low-stock` | Products below reorder threshold |
| `GET` | `/dashboard/{slug}/pharmacy/products/expiring` | Products expiring within 90 days |

**POST/PUT request body:**
```json
{
  "nameGeneric": "Amoxicillin",
  "nameBrand": "Amoxil",
  "slug": "amoxicillin-500mg",
  "description": "Broad-spectrum antibiotic for bacterial infections",
  "indications": "Upper respiratory tract infections, UTIs",
  "dosageGuidance": "500mg three times daily for 7 days",
  "warnings": "Do not use if allergic to penicillin",
  "storage": "Store below 25°C away from moisture",
  "price": 1500.00,
  "categoryId": "uuid",
  "requiresPrescription": true,
  "stockQty": 100,
  "nafdacNumber": "A4-1234",
  "brand": "GSK",
  "images": ["https://cdn.conddo.io/..."],
  "isActive": true,
  "expiryDate": "2027-12-31",
  "reorderLevel": 10
}
```

---

### Categories

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/dashboard/{slug}/pharmacy/categories` | List all categories |
| `POST` | `/dashboard/{slug}/pharmacy/categories` | Create category |
| `PUT` | `/dashboard/{slug}/pharmacy/categories/{id}` | Update category |
| `DELETE` | `/dashboard/{slug}/pharmacy/categories/{id}` | Delete category |

---

### Orders

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/dashboard/{slug}/pharmacy/orders` | List all orders (filterable by status) |
| `GET` | `/dashboard/{slug}/pharmacy/orders/{id}` | Get order detail |
| `PATCH` | `/dashboard/{slug}/pharmacy/orders/{id}/status` | Update order status |
| `POST` | `/dashboard/{slug}/pharmacy/orders/manual` | Create order manually (walk-in/phone) |

**PATCH status request body:**
```json
{
  "status": "PROCESSING",
  "note": "Preparing order for dispatch"
}
```

**Order statuses:** `PENDING` → `PROCESSING` → `READY` → `DISPATCHED` → `DELIVERED` → `CANCELLED`

**POST manual order body:**
```json
{
  "customerId": "uuid",
  "items": [
    { "productId": "uuid", "quantity": 2 }
  ],
  "paymentMethod": "cash",
  "notes": "Walk-in customer",
  "state": "Lagos"
}
```

---

### Prescriptions

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/dashboard/{slug}/pharmacy/prescriptions` | List all prescriptions (sorted: pending first) |
| `GET` | `/dashboard/{slug}/pharmacy/prescriptions/{id}` | Get prescription detail |
| `PATCH` | `/dashboard/{slug}/pharmacy/prescriptions/{id}/review` | Approve or reject prescription |

**PATCH review request body:**
```json
{
  "status": "APPROVED",
  "reviewNote": "Valid prescription. Dispense as written."
}
```

**Prescription statuses:** `PENDING` → `APPROVED` / `REJECTED`

---

### Customers

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/dashboard/{slug}/pharmacy/customers` | List all customers |
| `GET` | `/dashboard/{slug}/pharmacy/customers/{id}` | Get customer profile + order history |
| `POST` | `/dashboard/{slug}/pharmacy/customers` | Create customer manually (walk-in) |
| `PUT` | `/dashboard/{slug}/pharmacy/customers/{id}` | Update customer profile |
| `GET` | `/dashboard/{slug}/pharmacy/customers/{id}/health-profile` | Get health profile |
| `PUT` | `/dashboard/{slug}/pharmacy/customers/{id}/health-profile` | Update health profile |

**Health profile request body:**
```json
{
  "allergies": ["Penicillin", "Aspirin"],
  "chronicConditions": ["Hypertension", "Type 2 Diabetes"],
  "currentMedications": ["Amlodipine 5mg", "Metformin 500mg"],
  "bloodGroup": "O+",
  "notes": "Patient is pregnant. Avoid NSAIDs."
}
```

---

### Consultations

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/dashboard/{slug}/pharmacy/consultations` | List all consultation requests |
| `PATCH` | `/dashboard/{slug}/pharmacy/consultations/{id}/status` | Mark as completed or cancelled |

---

### Articles

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/dashboard/{slug}/pharmacy/articles` | List all articles (published + drafts) |
| `POST` | `/dashboard/{slug}/pharmacy/articles` | Create article |
| `PUT` | `/dashboard/{slug}/pharmacy/articles/{id}` | Update article |
| `PATCH` | `/dashboard/{slug}/pharmacy/articles/{id}/publish` | Publish or unpublish |
| `DELETE` | `/dashboard/{slug}/pharmacy/articles/{id}` | Delete article |

---

### Analytics

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/dashboard/{slug}/pharmacy/analytics/summary` | Revenue, orders, customers — today/week/month |
| `GET` | `/dashboard/{slug}/pharmacy/analytics/top-products` | Best selling products |
| `GET` | `/dashboard/{slug}/pharmacy/analytics/low-stock` | Products needing reorder |
| `GET` | `/dashboard/{slug}/pharmacy/analytics/expiring` | Expiry alerts (90/30/7 days) |

**Summary response:**
```json
{
  "summary": {
    "today": { "revenue": 45000, "orders": 12, "newCustomers": 3 },
    "thisWeek": { "revenue": 210000, "orders": 58, "newCustomers": 14 },
    "thisMonth": { "revenue": 780000, "orders": 201, "newCustomers": 47 }
  }
}
```

---

### Payment Link

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/dashboard/{slug}/pharmacy/orders/{id}/payment-link` | Generate and send Routepay payment link via SMS |

**Request body:**
```json
{
  "sendViaSms": true,
  "phoneNumber": "08012345678"
}
```

**Response:**
```json
{
  "paymentLink": "https://pay.routepay.com/xyz",
  "sent": true
}
```

---

## Section 13 — Webhooks (Routepay)

Conddo receives payment confirmation from Routepay and updates order payment status automatically.

### `POST /webhooks/routepay`

**Auth:** Routepay webhook signature validation (HMAC)  
**On successful payment:**
1. Find order by `payment_ref`
2. Update `paymentStatus` to `PAID`
3. Send customer confirmation via Brevo SMS and email
4. Publish `OrderPaid` event to Redis pub/sub
5. Dashboard updates in real time via SSE

---

## Section 14 — Refill Reminders (Scheduled)

Not an HTTP endpoint — this is a scheduled job that runs nightly.

**Logic:**
- Query all customers who purchased a chronic medication (e.g. Amlodipine, Metformin, Lisinopril) 25–30 days ago
- Check if they have not reordered since
- Send Brevo SMS: *"Hi [Name], your [medication] refill may be due. Reply YES to reorder or visit [website] to place your order."*
- Log reminder sent — do not send again for 7 days

---

## Implementation Notes for Backend Team

1. **Tenant isolation** — every query must filter by `tenantId`. Never trust the slug alone without resolving it to a tenant ID first.

2. **Stock check** — always validate stock at order submission. The cart is optimistic. The order endpoint is the source of truth.

3. **Prescription gate** — if any item in an order has `requiresPrescription: true`, the order must have a linked prescription with status `APPROVED` or `PENDING`. A `REJECTED` prescription must block the order.

4. **Offline POS orders** — the manual order endpoint (`POST /dashboard/{slug}/pharmacy/orders/manual`) must accept `paymentMethod: "cash"` and skip payment link generation entirely.

5. **Image uploads** — the site sends files to `/public/{slug}/upload` first, gets back a URL, then passes that URL to the product or prescription endpoint. Never accept raw file uploads on data endpoints.

6. **Delivery fee** — embed the Nigerian state delivery fee table directly in the Conddo backend. Do not call the Seb&Bayor codebase — use it as reference only.

7. **Brevo notifications** — all customer-facing notifications (order confirmation, prescription status, refill reminders, payment links) go through Brevo. Do not introduce any other notification provider.

8. **Events** — every significant action should publish to Redis pub/sub so the dashboard updates in real time. Key events: `OrderCreated`, `OrderPaid`, `OrderStatusUpdated`, `PrescriptionSubmitted`, `PrescriptionReviewed`, `ConsultationRequested`.

---

## Seb&Bayor Integration Checklist

Before the Seb&Bayor website goes live on Conddo, confirm:

- [ ] All Section 1–11 public endpoints are live and tested
- [ ] Tenant `seb-bayorpharmaceutical` is registered in `tenant_sites`
- [ ] Site API key issued and stored securely
- [ ] Seb&Bayor's website `app/api/` routes replaced with Conddo API calls
- [ ] Routepay keys configured for Seb&Bayor's tenant account
- [ ] Brevo sender configured for `info@sebandbayor.com.ng`
- [ ] MinIO bucket created for `seb-bayor` tenant
- [ ] Subdomain `seb-bayorpharmaceutical.conddo.io` pointing to Nginx
- [ ] SSL wildcard certificate covering `*.conddo.io` active
- [ ] Pharmacist dashboard account created for Seb&Bayor admin
- [ ] Existing products seeded from Seb&Bayor's current database
- [ ] QA sign-off on all endpoints before website goes live

---

*Questions? Raise on the internal board or flag to the product lead.*
