# Media upload staging lifecycle

The media API signs PUT requests only for `media-upload-staging/<uuid>`. After
HEAD verification, the application copies the object to its permanent
`listings/`, `posts/`, `products/`, or `review-media/` key and deletes all
known versions of the staging key. The published key is never a presigned PUT
destination.

This separation is necessary because a presigned URL is a bearer capability:
Amazon S3 checks expiry when the HTTP request starts, and an upload already in
progress can finish after the URL expiry. Therefore a late PUT must be confined
to the staging prefix and removed by the bucket lifecycle, rather than being
able to recreate a published object after account erasure.

## Required bucket rule

Apply an enabled one-day expiration rule to the exact prefix
`media-upload-staging/` before deploying the application migration/code.

- For Amazon S3, use
  `deploy/storage/aws-media-upload-staging-lifecycle.json`. Expire current
  versions after one day, abort incomplete multipart uploads, expire
  noncurrent versions, and remove expired delete markers. Object Lock/legal
  holds may prevent permanent deletion; the staging prefix must not inherit a
  retention lock.
- For Cloudflare R2, use
  `deploy/storage/r2-media-upload-staging-lifecycle.json`. R2 documents prefix
  filtering and object expiration and typically removes objects within 24
  hours of their `x-amz-expiration` time. R2's S3 compatibility matrix lists
  `CopyObject` and bucket lifecycle configuration as implemented, but bucket
  versioning operations as unimplemented.

The lifecycle configuration is bucket-wide configuration. Merge these rules
with existing rules; do not replace an existing lifecycle configuration
blindly. Cloudflare's Wrangler `lifecycle set` and S3
`PutBucketLifecycleConfiguration` set the bucket's rule collection. R2
lifecycle management requires the `Workers R2 Storage Write` permission group;
the runtime object token is not assumed to have this permission.

## Verification

Before accepting a production environment:

1. Read the bucket lifecycle configuration with the provider's documented
   read operation (R2: `npx wrangler r2 bucket lifecycle list <bucket>`, or
   `GetBucketLifecycleConfiguration`).
2. Verify an enabled rule whose filter prefix is exactly
   `media-upload-staging/` and whose expiration is one day.
3. For versioned AWS S3, verify noncurrent-version expiration and expired
   delete-marker cleanup too.
4. Verify staging keys are not subject to Object Lock or legal-hold retention.
5. Exercise an upload, confirm it, and verify that the staging key disappears
   while the permanent key remains. Then repeat with an account-erasure race:
   the account must remain unreadable, the final key must be deleted, and any
   late staging write must expire through the rule.

The application deliberately does not overwrite bucket lifecycle rules at
startup. Until the provider's live configuration is checked, infrastructure
cleanup is an operational prerequisite rather than a proven production fact.

## Legacy pending uploads

Migration V305 adds the nullable key columns; V306 preserves the
already-issued destination of rows that were `PENDING_UPLOAD` at migration
time. Their old presigned URLs cannot be moved to the staging namespace because
the signed URL already fixes its key. The configured TTL bounds when a new
request may START, not when an already-started PUT must finish: AWS checks URL
expiry when the HTTP request begins, and a transfer begun before expiry may
continue after it. Therefore TTL expiry alone is not proof that no in-flight
legacy PUT can still create the old destination key. Keep this legacy window
as an explicit residual erasure risk until in-flight requests are known to have
drained or are otherwise contained. New uploads use the staging namespace and
its object-age lifecycle rule; do not extend the legacy exception to new rows.
