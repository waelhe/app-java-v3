/**
 * @license
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * Cart Context
 * 
 * سياق سلة التسوق
 */

'use client';

import React, { createContext, useContext, useState, useEffect, useMemo } from 'react';
import { toast } from 'sonner';

interface CartItem {
  id: string;
  productId: string;
  name: string;
  price: number;
  quantity: number;
  image?: string;
}

interface CartContextType {
  items: CartItem[];
  cartCount: number;
  totalPrice: number;
  isOpen: boolean;
  setIsOpen: (open: boolean) => void;
  addItem: (item: Omit<CartItem, 'id'>) => void;
  removeItem: (id: string) => void;
  updateQuantity: (id: string, quantity: number) => void;
  clearCart: () => void;
}

const CartContext = createContext<CartContextType | undefined>(undefined);

function getInitialCart(): CartItem[] {
  if (typeof window === 'undefined') return [];
  const savedCart = localStorage.getItem('cart');
  if (savedCart) {
    try {
      return JSON.parse(savedCart);
    } catch (e) {
      console.error('Failed to load cart:', e);
    }
  }
  return [];
}

export function CartProvider({ children }: { children: React.ReactNode }) {
  const [items, setItems] = useState<CartItem[]>(getInitialCart);
  const [isOpen, setIsOpen] = useState(false);

  // Save cart to localStorage on change
  useEffect(() => {
    localStorage.setItem('cart', JSON.stringify(items));
  }, [items]);

  const cartCount = items.reduce((sum, item) => sum + item.quantity, 0);
  const totalPrice = items.reduce((sum, item) => sum + (item.price * item.quantity), 0);

  const addItem = (item: Omit<CartItem, 'id'>) => {
    setItems(prev => {
      const existingIndex = prev.findIndex(i => i.productId === item.productId);
      
      if (existingIndex >= 0) {
        const updated = [...prev];
        updated[existingIndex].quantity += item.quantity;
        toast.success('تم تحديث الكمية');
        return updated;
      }
      
      toast.success('تمت الإضافة للسلة');
      return [...prev, { ...item, id: crypto.randomUUID() }];
    });
  };

  const removeItem = (id: string) => {
    setItems(prev => prev.filter(item => item.id !== id));
    toast.success('تم الحذف من السلة');
  };

  const updateQuantity = (id: string, quantity: number) => {
    if (quantity <= 0) {
      removeItem(id);
      return;
    }
    
    setItems(prev => prev.map(item => 
      item.id === id ? { ...item, quantity } : item
    ));
  };

  const clearCart = () => {
    setItems([]);
    toast.success('تم تفريغ السلة');
  };

  return (
    <CartContext.Provider value={{
      items,
      cartCount,
      totalPrice,
      isOpen,
      setIsOpen,
      addItem,
      removeItem,
      updateQuantity,
      clearCart,
    }}>
      {children}
    </CartContext.Provider>
  );
}

export function useCart() {
  const context = useContext(CartContext);
  if (!context) {
    throw new Error('useCart must be used within a CartProvider');
  }
  return context;
}
