/**
 * API client جاهز للتكامل مع Backend الخاص بمشروع marketplace-app-java-v3.
 *
 * ملاحظات:
 * - الـ Backend يستخدم base path: /api/v1
 * - أغلب endpoints محمية وتتطلب JWT Bearer token
 */

const BASE_URL = process.env.NEXT_PUBLIC_API_URL || 'http://localhost:8080/api/v1';

interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE' | 'PATCH';
  body?: unknown;
  headers?: Record<string, string>;
  token?: string;
}

class ApiClient {
  private baseUrl: string;

  constructor(baseUrl: string) {
    this.baseUrl = baseUrl;
  }

  private getHeaders(token?: string, customHeaders?: Record<string, string>): HeadersInit {
    const headers: HeadersInit = {
      'Content-Type': 'application/json',
      ...customHeaders,
    };

    if (token) {
      headers['Authorization'] = `Bearer ${token}`;
    }

    return headers;
  }

  async request<T>(endpoint: string, options: RequestOptions = {}): Promise<T> {
    const { method = 'GET', body, headers: customHeaders, token } = options;

    const url = `${this.baseUrl}${endpoint}`;
    const headers = this.getHeaders(token, customHeaders);

    const config: RequestInit = {
      method,
      headers,
      credentials: 'include',
    };

    if (body && method !== 'GET') {
      config.body = JSON.stringify(body);
    }

    const response = await fetch(url, config);

    if (!response.ok) {
      const error = await response.json().catch(() => ({ message: 'Network error' }));
      throw new Error(error.message || `HTTP error! status: ${response.status}`);
    }

    return response.json();
  }

  get<T>(endpoint: string, token?: string): Promise<T> {
    return this.request<T>(endpoint, { method: 'GET', token });
  }

  post<T>(endpoint: string, body: unknown, token?: string): Promise<T> {
    return this.request<T>(endpoint, { method: 'POST', body, token });
  }

  put<T>(endpoint: string, body: unknown, token?: string): Promise<T> {
    return this.request<T>(endpoint, { method: 'PUT', body, token });
  }

  patch<T>(endpoint: string, body: unknown, token?: string): Promise<T> {
    return this.request<T>(endpoint, { method: 'PATCH', body, token });
  }

  delete<T>(endpoint: string, token?: string): Promise<T> {
    return this.request<T>(endpoint, { method: 'DELETE', token });
  }
}

export const api = new ApiClient(BASE_URL);

// ========== Types المطابقة للـ Backend الحالي ==========

export interface User {
  id: string;
  email: string;
  displayName: string;
  createdAt: string;
  updatedAt: string;
}

export interface Listing {
  id: string;
  title: string;
  description: string;
  category: string;
  price: number;
  currency: string;
  createdAt: string;
  updatedAt: string;
}

export interface Booking {
  id: string;
  listingId: string;
  status: string;
  notes: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface Review {
  id: string;
  bookingId: string;
  rating: number;
  comment: string;
  createdAt: string;
  updatedAt: string;
}

export interface ListingSummary {
  id: string;
  title: string;
  category: string;
  price: number;
  currency: string;
  providerName?: string | null;
  rating?: number | null;
}

export interface Conversation {
  id: string;
  bookingId: string;
  consumerId: string;
  providerId: string;
  createdAt: string;
  updatedAt: string;
}

export interface Message {
  id: string;
  conversationId: string;
  senderId: string;
  content: string;
  readAt?: string | null;
  createdAt: string;
}

export interface PaymentIntent {
  id: string;
  bookingId: string;
  amount: number;
  currency: string;
  status: string;
  createdAt: string;
  updatedAt: string;
}

export interface PagedResponse<T> {
  content: T[];
  pageNumber: number;
  pageSize: number;
  totalElements: number;
  totalPages: number;
  last: boolean;
}

// ========== Services ==========

export const identityService = {
  me: (token: string) => api.get<User>('/users/me', token),
};

export const catalogService = {
  list: (params?: { page?: number; size?: number; sort?: string }, token?: string) =>
    api.get<PagedResponse<Listing>>(`/listings${toQuery(params)}`, token),
  byCategory: (category: string, params?: { page?: number; size?: number; sort?: string }, token?: string) =>
    api.get<PagedResponse<Listing>>(`/listings/category/${encodeURIComponent(category)}${toQuery(params)}`, token),
  byProvider: (providerId: string, params?: { page?: number; size?: number; sort?: string }, token?: string) =>
    api.get<PagedResponse<Listing>>(`/listings/provider/${providerId}${toQuery(params)}`, token),
  byId: (id: string, token?: string) => api.get<Listing>(`/listings/${id}`, token),
  create: (data: { title: string; description: string; category: string; price: number; currency: string }, token: string) =>
    api.post<Listing>('/listings', data, token),
  update: (id: string, data: Partial<Listing>, token: string) => api.put<Listing>(`/listings/${id}`, data, token),
  activate: (id: string, token: string) => api.post<Listing>(`/listings/${id}/activate`, {}, token),
  pause: (id: string, token: string) => api.post<Listing>(`/listings/${id}/pause`, {}, token),
  archive: (id: string, token: string) => api.post<Listing>(`/listings/${id}/archive`, {}, token),
};

export const searchService = {
  search: (
    params?: { q?: string; category?: string; page?: number; size?: number; sort?: string },
    token?: string,
  ) => api.get<PagedResponse<ListingSummary>>(`/search${toQuery(params)}`, token),
  byCategory: (category: string, params?: { page?: number; size?: number; sort?: string }, token?: string) =>
    api.get<PagedResponse<ListingSummary>>(`/search/category/${encodeURIComponent(category)}${toQuery(params)}`, token),
};

export const bookingService = {
  byId: (id: string, token: string) => api.get<Booking>(`/bookings/${id}`, token),
  byConsumer: (consumerId: string, params?: { page?: number; size?: number }, token?: string) =>
    api.get<PagedResponse<Booking>>(`/bookings/consumer/${consumerId}${toQuery(params)}`, token),
  byProvider: (providerId: string, params?: { page?: number; size?: number }, token?: string) =>
    api.get<PagedResponse<Booking>>(`/bookings/provider/${providerId}${toQuery(params)}`, token),
  create: (data: { listingId: string; notes?: string }, token: string) => api.post<Booking>('/bookings', data, token),
  confirm: (id: string, token: string) => api.post<Booking>(`/bookings/${id}/confirm`, {}, token),
  complete: (id: string, token: string) => api.post<Booking>(`/bookings/${id}/complete`, {}, token),
  cancel: (id: string, token: string) => api.post<Booking>(`/bookings/${id}/cancel`, {}, token),
};

export const reviewsService = {
  byId: (id: string, token?: string) => api.get<Review>(`/reviews/${id}`, token),
  byProvider: (providerId: string, params?: { page?: number; size?: number }, token?: string) =>
    api.get<PagedResponse<Review>>(`/reviews/provider/${providerId}${toQuery(params)}`, token),
  byReviewer: (reviewerId: string, params?: { page?: number; size?: number }, token?: string) =>
    api.get<PagedResponse<Review>>(`/reviews/reviewer/${reviewerId}${toQuery(params)}`, token),
  create: (data: { bookingId: string; rating: number; comment: string }, token: string) =>
    api.post<Review>('/reviews', data, token),
  update: (id: string, data: { rating?: number; comment?: string }, token: string) =>
    api.put<Review>(`/reviews/${id}`, data, token),
};


export const messagingService = {
  conversationById: (id: string, token: string) => api.get<Conversation>(`/messages/conversations/${id}`, token),
  messages: (conversationId: string, params?: { page?: number; size?: number }, token?: string) =>
    api.get<PagedResponse<Message>>(`/messages/conversations/${conversationId}/messages${toQuery(params)}`, token),
  unreadCount: (conversationId: string, token: string) =>
    api.get<{ unreadCount: number }>(`/messages/conversations/${conversationId}/unread`, token),
  createConversation: (data: { bookingId: string }, token: string) =>
    api.post<Conversation>('/messages/conversations', data, token),
  send: (conversationId: string, data: { content: string }, token: string) =>
    api.post<Message>(`/messages/conversations/${conversationId}/messages`, data, token),
  markRead: (conversationId: string, token: string) =>
    api.post<void>(`/messages/conversations/${conversationId}/read`, {}, token),
};

export const paymentsService = {
  intentById: (id: string, token: string) => api.get<PaymentIntent>(`/payments/intents/${id}`, token),
  createIntent: (data: { bookingId: string; amount: number; currency: string }, token: string) =>
    api.post<PaymentIntent>('/payments/intents', data, token),
  processIntent: (id: string, token: string) => api.post<PaymentIntent>(`/payments/intents/${id}/process`, {}, token),
  confirmIntent: (id: string, token: string) => api.post<PaymentIntent>(`/payments/intents/${id}/confirm`, {}, token),
  cancelIntent: (id: string, token: string) => api.post<PaymentIntent>(`/payments/intents/${id}/cancel`, {}, token),
  refund: (paymentId: string, token: string) => api.post<void>(`/payments/${paymentId}/refund`, {}, token),
};

function toQuery(params?: Record<string, string | number | undefined>): string {
  if (!params) return '';
  const query = new URLSearchParams();

  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== null) {
      query.set(key, String(value));
    }
  });

  const serialized = query.toString();
  return serialized ? `?${serialized}` : '';
}
