// ========================================
// Qudsaya Plus Service Worker
// للعمل بدون اتصال بالإنترنت
// ========================================

const CACHE_NAME = 'qudsaya-plus-v1';
const STATIC_CACHE = 'qudsaya-static-v1';
const DYNAMIC_CACHE = 'qudsaya-dynamic-v1';
const IMAGE_CACHE = 'qudsaya-images-v1';

// الملفات الثابتة للتخزين المؤقت
const STATIC_ASSETS = [
  '/',
  '/manifest.json',
  '/logo.svg',
  '/offline.html',
];

// صفحات للتخزين المؤقت عند الطلب
const CACHEABLE_ROUTES = [
  '/marketplace',
  '/real-estate',
  '/directory',
  '/community',
  '/market-prices',
  '/islamic',
];

// تثبيت Service Worker
self.addEventListener('install', (event) => {
  console.log('[SW] Installing Service Worker...');
  
  event.waitUntil(
    caches.open(STATIC_CACHE)
      .then((cache) => {
        console.log('[SW] Caching static assets');
        return cache.addAll(STATIC_ASSETS);
      })
      .then(() => {
        console.log('[SW] Service Worker installed');
        return self.skipWaiting();
      })
      .catch((error) => {
        console.error('[SW] Failed to cache static assets:', error);
      })
  );
});

// تفعيل Service Worker
self.addEventListener('activate', (event) => {
  console.log('[SW] Activating Service Worker...');
  
  event.waitUntil(
    caches.keys()
      .then((cacheNames) => {
        return Promise.all(
          cacheNames
            .filter((cacheName) => {
              return cacheName !== STATIC_CACHE && 
                     cacheName !== DYNAMIC_CACHE && 
                     cacheName !== IMAGE_CACHE;
            })
            .map((cacheName) => {
              console.log('[SW] Deleting old cache:', cacheName);
              return caches.delete(cacheName);
            })
        );
      })
      .then(() => {
        console.log('[SW] Service Worker activated');
        return self.clients.claim();
      })
  );
});

// معالجة طلبات الشبكة
self.addEventListener('fetch', (event) => {
  const { request } = event;
  const url = new URL(request.url);

  // تجاهل طلبات غير HTTP
  if (!request.url.startsWith('http')) {
    return;
  }

  // تجاهل طلبات API (إلا GET)
  if (url.pathname.startsWith('/api/') && request.method !== 'GET') {
    return;
  }

  // معالجة الصور
  if (request.destination === 'image') {
    event.respondWith(handleImageRequest(request));
    return;
  }

  // معالجة طلبات التنقل
  if (request.mode === 'navigate') {
    event.respondWith(handleNavigationRequest(request));
    return;
  }

  // معالجة الطلبات الأخرى
  event.respondWith(handleFetchRequest(request));
});

// معالجة طلبات الصور
async function handleImageRequest(request) {
  const cache = await caches.open(IMAGE_CACHE);
  
  try {
    const cachedResponse = await cache.match(request);
    if (cachedResponse) {
      return cachedResponse;
    }

    const networkResponse = await fetch(request);
    
    if (networkResponse.ok) {
      cache.put(request, networkResponse.clone());
    }
    
    return networkResponse;
  } catch (error) {
    console.log('[SW] Image fetch failed, returning placeholder');
    // يمكن إرجاع صورة بديلة هنا
    return new Response('', { status: 404 });
  }
}

// معالجة طلبات التنقل
async function handleNavigationRequest(request) {
  const cache = await caches.open(DYNAMIC_CACHE);
  
  try {
    const networkResponse = await fetch(request);
    
    if (networkResponse.ok) {
      cache.put(request, networkResponse.clone());
    }
    
    return networkResponse;
  } catch (error) {
    console.log('[SW] Network failed, trying cache');
    
    const cachedResponse = await cache.match(request);
    if (cachedResponse) {
      return cachedResponse;
    }

    // محاولة إرجاع الصفحة الرئيسية كـ fallback
    const homeCached = await cache.match('/');
    if (homeCached) {
      return homeCached;
    }

    // إرجاع صفحة عدم الاتصال
    return caches.match('/offline.html');
  }
}

// معالجة الطلبات العامة
async function handleFetchRequest(request) {
  const cache = await caches.open(DYNAMIC_CACHE);
  
  // استراتيجية Network First للـ API
  if (request.url.includes('/api/')) {
    try {
      const networkResponse = await fetch(request);
      
      if (networkResponse.ok && request.method === 'GET') {
        cache.put(request, networkResponse.clone());
      }
      
      return networkResponse;
    } catch (error) {
      const cachedResponse = await cache.match(request);
      if (cachedResponse) {
        return cachedResponse;
      }
      
      return new Response(
        JSON.stringify({ error: 'غير متصل بالإنترنت' }),
        { 
          status: 503,
          headers: { 'Content-Type': 'application/json' }
        }
      );
    }
  }

  // استراتيجية Cache First للملفات الثابتة
  try {
    const cachedResponse = await cache.match(request);
    if (cachedResponse) {
      return cachedResponse;
    }

    const networkResponse = await fetch(request);
    
    if (networkResponse.ok) {
      cache.put(request, networkResponse.clone());
    }
    
    return networkResponse;
  } catch (error) {
    return new Response('Offline', { status: 503 });
  }
}

// استقبال الرسائل من التطبيق
self.addEventListener('message', (event) => {
  if (event.data && event.data.type === 'SKIP_WAITING') {
    self.skipWaiting();
  }

  if (event.data && event.data.type === 'CLEAR_CACHE') {
    event.waitUntil(
      caches.keys().then((cacheNames) => {
        return Promise.all(
          cacheNames.map((cacheName) => caches.delete(cacheName))
        );
      })
    );
  }
});

// معالجة الإشعارات Push
self.addEventListener('push', (event) => {
  console.log('[SW] Push notification received');
  
  let data = {
    title: 'قدسيا بلس',
    body: 'لديك إشعار جديد',
    icon: '/logo.svg',
    badge: '/logo.svg',
    dir: 'rtl',
    lang: 'ar',
  };

  if (event.data) {
    try {
      const pushData = event.data.json();
      data = { ...data, ...pushData };
    } catch (e) {
      data.body = event.data.text();
    }
  }

  const options = {
    body: data.body,
    icon: data.icon,
    badge: data.badge,
    dir: data.dir,
    lang: data.lang,
    vibrate: [100, 50, 100],
    data: {
      url: data.url || '/',
      dateOfArrival: Date.now(),
    },
    actions: [
      { action: 'open', title: 'فتح' },
      { action: 'close', title: 'إغلاق' },
    ],
  };

  event.waitUntil(
    self.registration.showNotification(data.title, options)
  );
});

// معالجة النقر على الإشعار
self.addEventListener('notificationclick', (event) => {
  console.log('[SW] Notification clicked');
  
  event.notification.close();

  if (event.action === 'close') {
    return;
  }

  const urlToOpen = event.notification.data?.url || '/';

  event.waitUntil(
    clients.matchAll({ type: 'window', includeUncontrolled: true })
      .then((clientList) => {
        // البحث عن نافذة مفتوحة
        for (const client of clientList) {
          if (client.url.includes(self.location.origin) && 'focus' in client) {
            client.navigate(urlToOpen);
            return client.focus();
          }
        }
        // فتح نافذة جديدة
        if (clients.openWindow) {
          return clients.openWindow(urlToOpen);
        }
      })
  );
});

// Background Sync للمهام المتأخرة
self.addEventListener('sync', (event) => {
  console.log('[SW] Background sync:', event.tag);
  
  if (event.tag === 'sync-favorites') {
    event.waitUntil(syncFavorites());
  }
  
  if (event.tag === 'sync-offline-data') {
    event.waitUntil(syncOfflineData());
  }
});

// مزامنة المفضلة
async function syncFavorites() {
  try {
    const cache = await caches.open(DYNAMIC_CACHE);
    const offlineFavorites = await cache.match('/offline-favorites');
    
    if (offlineFavorites) {
      const data = await offlineFavorites.json();
      
      for (const item of data) {
        await fetch('/api/favorites', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(item),
        });
      }
      
      await cache.delete('/offline-favorites');
    }
  } catch (error) {
    console.error('[SW] Failed to sync favorites:', error);
  }
}

// مزامنة البيانات المتأخرة
async function syncOfflineData() {
  // يمكن إضافة منطق مزامنة البيانات هنا
  console.log('[SW] Syncing offline data...');
}

console.log('[SW] Service Worker loaded');
