/**
 * File storage infrastructure: {@link com.marketplace.shared.storage.StorageService},
 * {@link com.marketplace.shared.storage.StorageProperties},
 * {@link com.marketplace.shared.storage.StorageException}, and related types.
 *
 * <p>Exposed as the {@code shared-storage} named interface so that the
 * {@code app} module's {@code StorageController} and the root application's
 * {@code @EnableConfigurationProperties} can reference these types without
 * violating Spring Modulith boundary rules.
 *
 * <p>Reference: https://docs.spring.io/spring-modulith/reference/fundamentals.html
 * "You can declare additional named interfaces by annotating a package with
 *  @NamedInterface."
 */
@org.springframework.modulith.NamedInterface("shared-storage")
package com.marketplace.shared.storage;
