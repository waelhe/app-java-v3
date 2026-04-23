/**
 * @license
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * useNotificationWebSocket Hook
 * 
 * Hook مخصص للتعامل مع خدمة الإشعارات عبر WebSocket
 * 
 * @module hooks/useNotificationWebSocket
 */

'use client'

import { useEffect, useState, useCallback, useRef } from 'react'
import { io, Socket } from 'socket.io-client'

// ==================== Types ====================

/**
 * أنواع الإشعارات
 */
export type NotificationType = 
  | 'booking.new'
  | 'booking.confirmed'
  | 'booking.cancelled'
  | 'booking.completed'
  | 'payment.success'
  | 'payment.failed'
  | 'refund.processed'
  | 'review.new'
  | 'review.response'
  | 'message.new'
  | 'listing.approved'
  | 'listing.rejected'
  | 'escrow.released'
  | 'dispute.opened'
  | 'system.announcement'
  | 'system.maintenance'

/**
 * الإشعار
 */
export interface Notification {
  id: string
  type: NotificationType
  title: string
  titleAr: string
  message: string
  messageAr: string
  data?: Record<string, unknown>
  createdAt: Date
  read: boolean
}

/**
 * رسالة الدردشة
 */
export interface ChatMessage {
  id: string
  conversationId: string
  senderId: string
  senderName: string
  content: string
  type: 'text' | 'image' | 'file' | 'system'
  createdAt: Date
  read: boolean
  readBy: string[]
}

/**
 * حدث الكتابة
 */
export interface TypingEvent {
  conversationId: string
  userId: string
  userName: string
  isTyping: boolean
}

/**
 * حالة الاتصال
 */
export interface ConnectionState {
  isConnected: boolean
  isReconnecting: boolean
  error: string | null
  socketId: string | null
}

/**
 * خيارات الاتصال
 */
export interface UseNotificationOptions {
  userId: string
  role?: 'user' | 'host' | 'company' | 'admin'
  autoConnect?: boolean
  onNotification?: (notification: Notification) => void
  onMessage?: (message: ChatMessage) => void
  onConnect?: () => void
  onDisconnect?: () => void
  onError?: (error: Error) => void
}

/**
 * قيمة الإرجاع
 */
export interface UseNotificationReturn {
  connection: ConnectionState
  notifications: Notification[]
  unreadCount: number
  onlineUsers: Record<string, boolean>
  typingUsers: Record<string, TypingEvent[]>
  
  // Actions
  connect: () => void
  disconnect: () => void
  markAsRead: (notificationId: string) => void
  markAllAsRead: () => void
  joinConversation: (conversationId: string) => void
  leaveConversation: (conversationId: string) => void
  sendMessage: (conversationId: string, content: string, type?: 'text' | 'image' | 'file') => void
  markMessagesRead: (conversationId: string, messageIds: string[]) => void
  startTyping: (conversationId: string, userName: string) => void
  stopTyping: (conversationId: string) => void
  sendNotification: (targetUserId: string, notification: Omit<Notification, 'id' | 'createdAt' | 'read'>) => void
  getOnlineStatus: (userIds: string[]) => void
  sendBookingNotification: (data: BookingNotificationData) => void
  sendPaymentNotification: (data: PaymentNotificationData) => void
}

/**
 * بيانات إشعار الحجز
 */
export interface BookingNotificationData {
  hostId?: string
  guestId?: string
  bookingId: string
  listingId?: string
  listingTitle?: string
  guestName?: string
  type: 'new' | 'confirmed' | 'cancelled' | 'completed'
  checkIn?: Date
  checkOut?: Date
  reason?: string
}

/**
 * بيانات إشعار الدفع
 */
export interface PaymentNotificationData {
  userId: string
  paymentId: string
  bookingId?: string
  amount: number
  currency: string
  type: 'success' | 'failed'
  reason?: string
}

// ==================== Hook ====================

export function useNotificationWebSocket({
  userId,
  role = 'user',
  autoConnect = true,
  onNotification,
  onMessage,
  onConnect,
  onDisconnect,
  onError,
}: UseNotificationOptions): UseNotificationReturn {
  const socketRef = useRef<Socket | null>(null)
  
  const [connection, setConnection] = useState<ConnectionState>({
    isConnected: false,
    isReconnecting: false,
    error: null,
    socketId: null,
  })
  
  const [notifications, setNotifications] = useState<Notification[]>([])
  const [onlineUsers, setOnlineUsers] = useState<Record<string, boolean>>({})
  const [typingUsers, setTypingUsers] = useState<Record<string, TypingEvent[]>>({})
  
  const unreadCount = notifications.filter(n => !n.read).length

  // ==================== Connection Management ====================

  const connect = useCallback(() => {
    if (socketRef.current?.connected) return

    // Connect with XTransformPort for gateway
    const socketInstance = io('/?XTransformPort=3003', {
      transports: ['websocket', 'polling'],
      auth: {
        userId,
        role,
      },
      forceNew: true,
      reconnection: true,
      reconnectionAttempts: 10,
      reconnectionDelay: 1000,
      reconnectionDelayMax: 5000,
      timeout: 10000,
    })

    socketRef.current = socketInstance

    // Connection events
    socketInstance.on('connect', () => {
      setConnection({
        isConnected: true,
        isReconnecting: false,
        error: null,
        socketId: socketInstance.id,
      })
      onConnect?.()
    })

    socketInstance.on('disconnect', (reason) => {
      setConnection(prev => ({
        ...prev,
        isConnected: false,
        socketId: null,
        isReconnecting: reason === 'io client disconnect' ? false : true,
      }))
      onDisconnect?.()
    })

    socketInstance.on('connect_error', (error) => {
      setConnection(prev => ({
        ...prev,
        error: error.message,
        isReconnecting: true,
      }))
      onError?.(error)
    })

    socketInstance.on('reconnect', () => {
      setConnection(prev => ({
        ...prev,
        isReconnecting: false,
        error: null,
      }))
    })

    socketInstance.on('reconnect_error', (error) => {
      setConnection(prev => ({
        ...prev,
        error: error.message,
      }))
    })

    // ==================== Notification Events ====================

    socketInstance.on('notification:new', (notification: Notification) => {
      setNotifications(prev => [notification, ...prev])
      onNotification?.(notification)
    })

    socketInstance.on('notification:read', (data: { notificationId: string }) => {
      setNotifications(prev =>
        prev.map(n => n.id === data.notificationId ? { ...n, read: true } : n)
      )
    })

    // ==================== Chat Events ====================

    socketInstance.on('message:new', (message: ChatMessage) => {
      onMessage?.(message)
    })

    socketInstance.on('message:read', (data: { conversationId: string; messageIds: string[]; readBy: string }) => {
      // Handle message read status
    })

    socketInstance.on('typing:update', (event: TypingEvent) => {
      setTypingUsers(prev => {
        const current = prev[event.conversationId] || []
        const filtered = current.filter(t => t.userId !== event.userId)
        if (event.isTyping) {
          return { ...prev, [event.conversationId]: [...filtered, event] }
        }
        return { ...prev, [event.conversationId]: filtered }
      })
    })

    socketInstance.on('conversation:joined', (data: { conversationId: string }) => {
      // Handle conversation join
    })

    socketInstance.on('conversation:left', (data: { conversationId: string }) => {
      // Handle conversation leave
    })

    // ==================== Presence Events ====================

    socketInstance.on('presence:online-status', (status: Record<string, boolean>) => {
      setOnlineUsers(prev => ({ ...prev, ...status }))
    })

    socketInstance.on('presence:offline', (data: { userId: string }) => {
      setOnlineUsers(prev => {
        const updated = { ...prev }
        delete updated[data.userId]
        return updated
      })
    })

    // ==================== Server Events ====================

    socketInstance.on('server:shutdown', (data: { message: string; reconnectIn: number }) => {
      setConnection(prev => ({
        ...prev,
        error: data.message,
        isReconnecting: true,
      }))
    })

    socketInstance.on('error', (error: { message: string }) => {
      setConnection(prev => ({
        ...prev,
        error: error.message,
      }))
      onError?.(new Error(error.message))
    })
  }, [userId, role, onNotification, onMessage, onConnect, onDisconnect, onError])

  const disconnect = useCallback(() => {
    if (socketRef.current) {
      socketRef.current.disconnect()
      socketRef.current = null
    }
    setConnection({
      isConnected: false,
      isReconnecting: false,
      error: null,
      socketId: null,
    })
  }, [])

  // ==================== Notification Actions ====================

  const markAsRead = useCallback((notificationId: string) => {
    if (socketRef.current) {
      socketRef.current.emit('notification:mark-read', { notificationId })
    }
  }, [])

  const markAllAsRead = useCallback(() => {
    notifications.forEach(n => {
      if (!n.read) {
        markAsRead(n.id)
      }
    })
  }, [notifications, markAsRead])

  const sendNotification = useCallback((
    targetUserId: string,
    notification: Omit<Notification, 'id' | 'createdAt' | 'read'>
  ) => {
    if (socketRef.current) {
      socketRef.current.emit('notification:send', {
        targetUserId,
        ...notification,
      })
    }
  }, [])

  // ==================== Chat Actions ====================

  const joinConversation = useCallback((conversationId: string) => {
    if (socketRef.current) {
      socketRef.current.emit('conversation:join', { conversationId })
    }
  }, [])

  const leaveConversation = useCallback((conversationId: string) => {
    if (socketRef.current) {
      socketRef.current.emit('conversation:leave', { conversationId })
    }
  }, [])

  const sendMessage = useCallback((
    conversationId: string,
    content: string,
    type: 'text' | 'image' | 'file' = 'text'
  ) => {
    if (socketRef.current) {
      socketRef.current.emit('message:send', {
        conversationId,
        content,
        type,
        senderName: '', // Should be provided by caller
      })
    }
  }, [])

  const markMessagesRead = useCallback((conversationId: string, messageIds: string[]) => {
    if (socketRef.current) {
      socketRef.current.emit('message:mark-read', { conversationId, messageIds })
    }
  }, [])

  const startTyping = useCallback((conversationId: string, userName: string) => {
    if (socketRef.current) {
      socketRef.current.emit('typing:start', { conversationId, userName })
    }
  }, [])

  const stopTyping = useCallback((conversationId: string) => {
    if (socketRef.current) {
      socketRef.current.emit('typing:stop', { conversationId })
    }
  }, [])

  // ==================== Presence Actions ====================

  const getOnlineStatus = useCallback((userIds: string[]) => {
    if (socketRef.current) {
      socketRef.current.emit('presence:get-online', { userIds })
    }
  }, [])

  // ==================== Business Actions ====================

  const sendBookingNotification = useCallback((data: BookingNotificationData) => {
    if (!socketRef.current) return

    switch (data.type) {
      case 'new':
        socketRef.current.emit('booking:new', {
          hostId: data.hostId,
          bookingId: data.bookingId,
          listingId: data.listingId,
          guestName: data.guestName,
          checkIn: data.checkIn,
          checkOut: data.checkOut,
        })
        break
      case 'confirmed':
        socketRef.current.emit('booking:confirmed', {
          guestId: data.guestId,
          bookingId: data.bookingId,
          listingTitle: data.listingTitle,
        })
        break
      case 'cancelled':
        socketRef.current.emit('booking:cancelled', {
          targetUserId: data.guestId || data.hostId,
          bookingId: data.bookingId,
          listingTitle: data.listingTitle,
          reason: data.reason,
        })
        break
      case 'completed':
        socketRef.current.emit('booking:completed', {
          guestId: data.guestId,
          bookingId: data.bookingId,
          listingTitle: data.listingTitle,
        })
        break
    }
  }, [])

  const sendPaymentNotification = useCallback((data: PaymentNotificationData) => {
    if (!socketRef.current) return

    if (data.type === 'success') {
      socketRef.current.emit('payment:success', {
        userId: data.userId,
        paymentId: data.paymentId,
        bookingId: data.bookingId,
        amount: data.amount,
        currency: data.currency,
      })
    } else {
      socketRef.current.emit('payment:failed', {
        userId: data.userId,
        paymentId: data.paymentId,
        amount: data.amount,
        currency: data.currency,
        reason: data.reason,
      })
    }
  }, [])

  // ==================== Lifecycle ====================

  useEffect(() => {
    if (autoConnect && userId) {
      connect()
    }

    return () => {
      disconnect()
    }
  }, [autoConnect, userId, connect, disconnect])

  // ==================== Return ====================

  return {
    connection,
    notifications,
    unreadCount,
    onlineUsers,
    typingUsers,
    connect,
    disconnect,
    markAsRead,
    markAllAsRead,
    joinConversation,
    leaveConversation,
    sendMessage,
    markMessagesRead,
    startTyping,
    stopTyping,
    sendNotification,
    getOnlineStatus,
    sendBookingNotification,
    sendPaymentNotification,
  }
}

// ==================== Context Provider Hook ====================

/**
 * Hook مبسط للحصول على Socket مباشرة
 */
export function useSocket() {
  const socketRef = useRef<Socket | null>(null)

  const connect = useCallback((userId: string, role: string = 'user') => {
    if (socketRef.current?.connected) return socketRef.current

    const socket = io('/?XTransformPort=3003', {
      transports: ['websocket', 'polling'],
      auth: { userId, role },
    })

    socketRef.current = socket
    return socket
  }, [])

  const disconnect = useCallback(() => {
    if (socketRef.current) {
      socketRef.current.disconnect()
      socketRef.current = null
    }
  }, [])

  const getSocket = useCallback(() => socketRef.current, [])

  return { connect, disconnect, getSocket }
}

export default useNotificationWebSocket
