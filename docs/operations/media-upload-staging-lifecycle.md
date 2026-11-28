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

## Legacy direct PUT URLs

Before the staging rollout, upload URLs addressed the permanent `object_key`
directly. This applied both to rows still in `PENDING_UPLOAD` and to rows
already confirmed as `UPLOADED`: the URL was reusable until expiry and a PUT
could replace the same key after confirmation. V306 therefore preserves that
historical upload destination in `upload_object_key` for every non-`PURGED`
row in both media tables, not only pending uploads.

The signed URL cannot be moved or revoked by a database migration. Its
configured TTL bounds when a new request may START, not when an already-started
PUT must finish: AWS checks URL expiry when the HTTP request begins, and a
transfer begun before expiry may continue after it. Thus TTL expiry alone is
not proof that no in-flight legacy PUT can recreate a permanent key after the
erasure adapter has deleted it. The old direct-upload capability remains an
explicit residual erasure risk until legacy requests are known to have drained
or an owner-approved containment mechanism is in place. No production bucket
rule or upload-drain telemetry is assumed by this application change.

The new staging namespace applies only to URLs issued after the rollout. It
confines reusable and late PUTs to `media-upload-staging/`, so the configured
object-age lifecycle can remove them without making published media keys
writable. Do not treat the new staging rule as revocation of a previously
issued legacy URL.
